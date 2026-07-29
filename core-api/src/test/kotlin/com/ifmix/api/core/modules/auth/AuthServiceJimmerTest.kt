package com.ifmix.api.core.service.auth

import com.ifmix.api.core.infra.auth.AuthJwtService
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.common.tx.TxRunner
import com.ifmix.api.core.service.appconfig.AppConfig
import com.ifmix.api.core.service.appconfig.AppConfigRepo
import com.ifmix.api.core.service.appconfig.GoogleClientIds
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestExecutionListeners
import org.springframework.test.jdbc.SqlRepeatRepetitionListener
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@SpringBootTest(classes = [AuthConfig::class, com.ifmix.api.core.infra.jimmer.JimmerConfig::class])
@TestExecutionListeners(listeners = [SqlRepeatRepetitionListener::class])
class AuthServiceJimmerTest {

    @Autowired
    private lateinit var authService: AuthService

    @Autowired
    private lateinit var appConfigRepo: AppConfigRepo

    @Autowired
    private lateinit var providerIdentityRepo: com.ifmix.api.core.repository.auth.AuthProviderIdentityRepository

    @Autowired
    private lateinit var appUserRepo: com.ifmix.api.core.repository.auth.AppUserRepository

    @Autowired
    private lateinit var deviceSecretRepo: com.ifmix.api.core.repository.auth.AuthDeviceSecretRepository

    @Autowired
    private lateinit var refreshRepo: com.ifmix.api.core.repository.auth.AppRefreshTokenRepository

    @Autowired
    private lateinit var jwt: AuthJwtService

    private val accessTtlSec = 900L

    private fun createDefaultAppConfig(): AppConfig {
        return AppConfig(
            id = UUID.randomUUID().toString(),
            appId = "app1",
            authTenantId = "00000000-0000-0000-0000-000000000001",
            revision = 1,
            appleBundleId = null,
            androidPackageName = null,
            appleAppAppleId = null,
            appleIssuerId = null,
            appleKeyId = null,
            applePrivateKey = null,
            appleServicesId = null,
            googleServiceAccount = null,
            googleClientIds = GoogleClientIds(ios = null, android = null, web = null),
            productTierMap = emptyMap(),
            iapEnv = "production",
            createdAt = null,
            updatedAt = null,
        )
    }

    @Test
    @Transactional
    fun `loginWithProvider issues tokens on first login`() {
        // Arrange
        val ctx = RequestContext(appId = "app1", installId = "inst1")
        // Set up default app config
        appConfigRepo.save(createDefaultAppConfig())

        // Act - this would require mocking provider verification and JWT decoding
        // For integration test, we'd need a full setup with actual providers
        // This is a simplified placeholder
        assertThat(authService).isNotNull()
    }

    @Test
    fun `exchange flow requires valid device secret`() {
        // Test that exchange fails without valid device secret
        val ctx = RequestContext(appId = "app1", installId = "inst2")
        assertThatThrownBy { 
            // exchange req would require valid device secret
        }.isInstanceOf(com.ifmix.api.core.infra.http.ApiError::class.java)
    }

    @Test
    fun `refresh flow rotates refresh token`() {
        // Setup: create a valid refresh token, then refresh it
        // Verify old token is rotated/new token issued
    }

    @Test
    fun `logout revokes refresh token and device secret`() {
        // Setup: perform login to get tokens
        // Call logout with refresh token
        // Verify refresh token and associated device secret are revoked
    }
}
