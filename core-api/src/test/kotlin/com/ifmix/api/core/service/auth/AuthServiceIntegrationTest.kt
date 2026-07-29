package com.ifmix.api.core.service.auth

import com.ifmix.api.core.infra.auth.AuthJwtKeys
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.repository.auth.*
import com.ifmix.api.core.service.appconfig.AppConfig
import com.ifmix.api.core.service.appconfig.GoogleClientIds
import com.ifmix.api.core.repository.appconfig.AppConfigRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterEach
import static org.assertj.core.api.Assertions.assertThat
import org.springframework.transaction.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFailsWith

// Integration tests for AuthService using Testcontainers + PostgreSQL
class AuthServiceIntegrationTest : AbstractJimmerTest() {

    private lateinit var authService:AuthService
    private lateinit var appConfigRepo:AppConfigRepo
    private lateinit var providerIdentityRepo:AuthProviderIdentityRepository
    private lateinit var identityRepo:AuthIdentityRepository
    private lateinit var appUserRepo:AppUserRepository
    private lateinit var deviceSecretRepo:AuthDeviceSecretRepository
    private lateinit var refreshRepo:AppRefreshTokenRepository
    private lateinit var jwt:AuthJwtService

    private val accessTtlSec = 900L
    private val appId = "00000000-0000-0000-0000-000000000001"
    private val installId = "test-install-1"
    private var tenantId:UUID? = null

    private val testPrivateKey = """
        {
          "kty": "oct",
          "kid": "test-key",
          "k": "Gaw7guWXluORSKh2y6qkiwAFWyd_HD2sLB-dNSsGHgA"
        }
    """.trimIndent()

    @BeforeAll
    fun setUp() {
        // Initialize database with Flyway
        val registry = createTestRegistry()
        Flyway.configure()
            .dataSource(registry.properties.writer.jdbcUrl, registry.properties.writer.username, registry.properties.writer.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val client = registry.sqlClient

        // Create auth tenant first
        tenantId = UUID.randomUUID()
        val tenantRepo = com.ifmix.api.core.repository.auth.AuthTenantRepository(client)
        tenantRepo.save(com.ifmix.api.core.entity.auth.AuthTenant {
            id = tenantId!!
            createdAt = Instant.now()
            updatedAt = Instant.now()
        })

        // Initialize repositories
        identityRepo = AuthIdentityRepository(client)
        providerIdentityRepo = AuthProviderIdentityRepository(client)
        appUserRepo = AppUserRepository(client)
        deviceSecretRepo = AuthDeviceSecretRepository(client)
        refreshRepo = AppRefreshTokenRepository(client)

        // Setup mock AppConfigRepo that returns our config with the created tenant
        val realConfigRepo = AppConfigRepository(client)
        appConfigRepo = object : AppConfigRepo(realConfigRepo) {
            override fun getByAppId(appId: String?): AppConfig? {
                return AppConfig(
                    id = "c",
                    appId = appId ?: "",
                    authTenantId = tenantId?.toString(),
                    revision = 1,
                    appleBundleId = null,
                    androidPackageName = null,
                    appleAppAppleId = null,
                    appleIssuerId = null,
                    appleKeyId = null,
                    applePrivateKey = null,
                    appleServicesId = null,
                    googleServiceAccount = null,
                    googleClientIds = GoogleClientIds(),
                    productTierMap = emptyMap(),
                    iapEnv = "production",
                    createdAt = null,
                    updatedAt = null,
                )
            }
        }

        // Create JWT service
        jwt = AuthJwtService(AuthJwtKeys(testPrivateKey), "ifmix", accessTtlSec)

        // Create mock verifier
        val googleVerifier = object : ProviderVerifier {
            override val provider = "google"
            override fun verify(config: AppConfig, platform: ClientPlatform?, idToken: String): VerifiedProvider {
                return VerifiedProvider(
                    accountId = "user123",
                    email = "test@example.com",
                    emailVerified = true,
                    phone = "+1234567890",
                    userMetadata = mapOf("name" to "Test User"),
                )
            }
        }
        val verifiers = mapOf("google" to googleVerifier)

        // Build AuthService
        authService = AuthService(
            appConfigRepo = appConfigRepo,
            providerVerifiers = verifiers,
            authJwtService = jwt,
            providerIdentityRepo = providerIdentityRepo,
            appUserRepo = appUserRepo,
            deviceSecretRepo = deviceSecretRepo,
            refreshRepo = refreshRepo,
            identityRepo = identityRepo,
            events = SimpleEventPublisher(),
            accessTtlSec = accessTtlSec,
        )
    }

    @BeforeEach
    fun beforeEach() {
        RequestContextHolder.createRequestContext(appId, installId)
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.clear()
    }

    @Test
    @Transactional
    fun `loginWithProvider issues tokens on first login`() {
        // Arrange
        val ctx = RequestContext(appId = appId, installId = installId)

        // Act
        val req = LoginReq(idToken = "fake-token", deviceSecret = null)
        val result = authService.loginWithProvider(ctx, "google", req)

        // Assert
        assertThat(result.accessToken).isNotNull().isNotEmpty()
        assertThat(result.refreshToken).isNotNull().isNotEmpty()
        assertThat(result.deviceSecret).isNotNull().isNotEmpty()
        assertThat(result.expiresIn).isGreaterThan(0)
        assertThat(result.user).isNotNull()
        assertThat(result.user.id).isNotNull()
        assertThat(result.user.email).isEqualTo("test@example.com")
        assertThat(result.refreshExpiresAt).isGreaterThan(Instant.now())
    }

    @Test
    @Transactional
    fun `refresh flow rotates refresh token correctly`() {
        // Arrange: perform login first
        val ctx = RequestContext(appId = appId, installId = installId)
        val loginReq = LoginReq(idToken = "fake-token", deviceSecret = null)
        val loginResult = authService.loginWithProvider(ctx, "google", loginReq)

        // Act: refresh the token
        val refreshReq = RefreshReq(refreshToken = loginResult.refreshToken)
        val refreshResult = authService.refresh(ctx, refreshReq)

        // Assert
        assertThat(refreshResult.accessToken).isNotNull().isNotEmpty()
        assertThat(refreshResult.refreshToken).isNotNull().isNotEmpty()
        assertThat(refreshResult.refreshToken).isNotEqualTo(loginResult.refreshToken)
        assertThat(refreshResult.refreshExpiresAt).isGreaterThan(Instant.now())

        // Verify old refresh token was revoked
        val oldTokenHash = Hashing.sha256Base64Url(loginResult.refreshToken)
        val oldToken = refreshRepo.findByHash(UUID.fromString(appId), oldTokenHash)
        assertThat(oldToken?.revokedAt).isNotNull()
        assertThat(oldToken?.replacedBy).isNotNull()
    }

    @Test
    @Transactional
    fun `logout revokes refresh token and associated device secret`() {
        // Arrange: perform login
        val ctx = RequestContext(appId = appId, installId = installId)
        val loginReq = LoginReq(idToken = "fake-token", deviceSecret = null)
        val loginResult = authService.loginWithProvider(ctx, "google", loginReq)

        // Act: logout
        val logoutReq = LogoutReq(refreshToken = loginResult.refreshToken)
        val logoutResult = authService.logout(ctx, logoutReq)

        // Assert
        assertThat(logoutResult.ok).isTrue()

        // Verify refresh token was revoked
        val refreshTokenHash = Hashing.sha256Base64Url(loginResult.refreshToken)
        val refreshTokenEntity = refreshRepo.findByHash(UUID.fromString(appId), refreshTokenHash)
        assertThat(refreshTokenEntity?.revokedAt).isNotNull()

        // Verify device secret was revoked (should be no longer valid)
        val deviceSecretHash = Hashing.sha256Base64Url(loginResult.deviceSecret)
        val validDevice = deviceSecretRepo.findValid(tenantId!!, deviceSecretHash)
        assertThat(validDevice).isNull()
    }

    @Test
    @Transactional
    fun `exchange flow with valid device secret issues new tokens`() {
        // Arrange: perform login first
        val ctx = RequestContext(appId = appId, installId = installId)
        val loginReq = LoginReq(idToken = "fake-token", deviceSecret = null)
        val loginResult = authService.loginWithProvider(ctx, "google", loginReq)

        // Act: exchange device secret for new tokens
        val exchangeReq = ExchangeReq(deviceSecret = loginResult.deviceSecret)
        val exchangeResult = authService.exchange(ctx, exchangeReq)

        // Assert
        assertThat(exchangeResult.accessToken).isNotNull().isNotEmpty()
        assertThat(exchangeResult.refreshToken).isNotNull().isNotEmpty()
        assertThat(exchangeResult.refreshExpiresAt).isGreaterThan(Instant.now())
        assertThat(exchangeResult.user).isNotNull()
        assertThat(exchangeResult.user.id).isNotNull()

        // Old refresh token should have been revoked due to cleanup in exchange
        val oldTokenHash = Hashing.sha256Base64Url(loginResult.refreshToken)
        val oldToken = refreshRepo.findByHash(UUID.fromString(appId), oldTokenHash)
        if (oldToken != null) {
            assertThat(oldToken?.revokedAt).isNotNull()
        }
    }

    @Test
    @Transactional
    fun `loginWithProvider is idempotent for same provider+accountId`() {
        // Arrange: first login
        val ctx1 = RequestContext(appId = appId, installId = "install-1")
        val loginReq1 = LoginReq(idToken = "fake-token-1", deviceSecret = null)
        val result1 = authService.loginWithProvider(ctx1, "google", loginReq1)

        // Second login with same provider and account (same email)
        val ctx2 = RequestContext(appId = appId, installId = "install-2")
        val loginReq2 = LoginReq(idToken = "fake-token-2", deviceSecret = null)
        val result2 = authService.loginWithProvider(ctx2, "google", loginReq2)

        // Assert: same userId (same identity), different tokens
        assertThat(result1.user.id).isEqualTo(result2.user.id)
        assertThat(result1.accessToken).isNotEqualTo(result2.accessToken)
        assertThat(result1.refreshToken).isNotEqualTo(result2.refreshToken)
        assertThat(result1.deviceSecret).isNotEqualTo(result2.deviceSecret)
    }

    @Test
    @Transactional
    fun `refresh rejects expired refresh token`() {
        // Arrange: create an expired refresh token directly in DB
        val ctx = RequestContext(appId = appId, installId = installId)
        val now = Instant.now()
        val past = now.minusSeconds(3600)

        // First ensure we have a valid appUser
        val identity = identityRepo.findByTenantAndEmail(tenantId!!, "test@example.com")
        val appUserId = if (identity != null) {
            appUserRepo.ensure(appId, identity.id)
        } else {
            // Fallback: create a fake one by doing a login first
            val fakeCtx = RequestContext(appId = appId, installId = installId)
            val fakeResult = authService.loginWithProvider(fakeCtx, "google", LoginReq(idToken = "fake", deviceSecret = null))
            UUID.fromString(fakeResult.user.id)
        }

        val expiredToken = UUID.randomUUID()
        val expiredHash = Hashing.sha256Base64Url("expired-token-placeholder")
        val expiredEntity = AppRefreshToken {
            id = expiredToken
            appId = UUID.fromString(appId)
            appUser = AppUser { id = appUserId }
            tokenHash = expiredHash
            expiresAt = past
            revokedAt = null
            loginInstallId = installId
            createdAt = now
            updatedAt = now
        }
        refreshRepo.save(expiredEntity)

        // Act: try to refresh with expired token
        val refreshReq = RefreshReq(refreshToken = "expired-token-placeholder")
        assertFailsWith<ApiError> {
            authService.refresh(ctx, refreshReq)
        }
    }

    @Test
    @Transactional
    fun `refresh rejects revoked refresh token`() {
        // Arrange: create a revoked refresh token
        val ctx = RequestContext(appId = appId, installId = installId)
        val now = Instant.now()

        // Get a valid appUser from a login
        val loginResult = authService.loginWithProvider(ctx, "google", LoginReq(idToken = "fake", deviceSecret = null))
        val appUserId = UUID.fromString(loginResult.user.id)

        // Create a revoked token
        val revokedToken = UUID.randomUUID()
        val revokedHash = Hashing.sha256Base64Url("revoked-token")
        val revokedEntity = AppRefreshToken {
            id = revokedToken
            appId = UUID.fromString(appId)
            appUser = AppUser { id = appUserId }
            tokenHash = revokedHash
            expiresAt = now.plusSeconds(3600)
            revokedAt = now
            loginInstallId = installId
            createdAt = now
            updatedAt = now
        }
        refreshRepo.save(revokedEntity)

        // Act: try to refresh with revoked token
        val refreshReq = RefreshReq(refreshToken = "revoked-token")
        assertFailsWith<ApiError> {
            authService.refresh(ctx, refreshReq)
        }
    }

    companion object {
        private var current: RequestContext? = null

        fun RequestContext(): RequestContext? = current
        set(value: RequestContext?) { current = value }

        fun RequestContext(set: RequestContext.() -> Unit) {
            current = RequestContext(appId = appId).apply(set)
        }
    }
}

// Simple event publisher for test
class SimpleEventPublisher : ApplicationEventPublisher {
    override fun publishEvent(event: Any?) {}
    override fun <T> publishEvent(event: T?) {}
}
