package com.ifmix.api.core.service.auth

import com.ifmix.api.core.infra.auth.AuthJwtService
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.common.tx.TxRunner
import com.ifmix.api.core.service.appconfig.AppConfig
import com.ifmix.api.core.service.appconfig.AppConfigRepo
import com.ifmix.api.core.service.appconfig.GoogleClientIds
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant

private fun ctx() = RequestContext(appId = "app1", installId = "inst1")

private fun appConfig(): AppConfig = AppConfig(
    id = "c", appId = "app1", authTenantId = "t1", revision = 1, appleBundleId = null,
    androidPackageName = null, appleAppAppleId = null, appleIssuerId = null, appleKeyId = null,
    applePrivateKey = null, appleServicesId = null, googleServiceAccount = null,
    googleClientIds = GoogleClientIds(), productTierMap = emptyMap(), iapEnv = "production",
    createdAt = null, updatedAt = null,
)

class AuthServiceTest {
    private val appConfigRepo = mock<AppConfigRepo>()
    private val verifier = mock<ProviderVerifier> { on { provider } doReturn "google" }
    private val providerIdentityRepo = mock<AuthProviderIdentityRepo>()
    private val appUserRepo = mock<AppUserRepo>()
    private val deviceSecretRepo = mock<AuthDeviceSecretRepo>()
    private val refreshRepo = mock<AppRefreshTokenRepo>()
    private val jwt = mock<AuthJwtService>()
    private val events = mock<ApplicationEventPublisher>()
    private val tx = mock<TxRunner> {
        on { withTx<Any>(any(), any()) } doAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            (inv.arguments[1] as (RequestContext) -> Any).invoke(inv.arguments[0] as RequestContext)
        }
    }
    private val svc = AuthService(
        appConfigRepo, mapOf("google" to verifier), jwt, providerIdentityRepo, appUserRepo,
        deviceSecretRepo, refreshRepo, tx, events, accessTtlSec = 900,
    )

    @Test fun `login happy path issues tokens and emits event`() {
        whenever(appConfigRepo.getByAppId("app1")).thenReturn(appConfig())
        whenever(verifier.verify(any(), anyOrNull(), any()))
            .thenReturn(VerifiedProvider("acc1", "a@b.com", true, null, emptyMap()))
        whenever(providerIdentityRepo.upsert(eq("t1"), any())).thenReturn("identity1")
        whenever(appUserRepo.ensure("app1", "identity1")).thenReturn("user1")
        whenever(deviceSecretRepo.issue("t1", "identity1", "inst1")).thenReturn("ds1" to "secretPlain")
        whenever(refreshRepo.issue(eq("app1"), eq("user1"), eq("ds1"), eq("inst1"), anyOrNull()))
            .thenReturn(RefreshIssued("r1", "refreshPlain", Instant.now().plusSeconds(100)))
        whenever(jwt.signAccess("user1", "app1")).thenReturn("access.jwt")

        val res = svc.loginWithProvider(ctx(), "google", LoginReq(idToken = "idtok"))

        assertThat(res.accessToken).isEqualTo("access.jwt")
        assertThat(res.refreshToken).isEqualTo("refreshPlain")
        assertThat(res.deviceSecret).isEqualTo("secretPlain")
        assertThat(res.user.id).isEqualTo("user1")
        verify(events).publishEvent(AuthLoggedInEvent("app1", "identity1", "user1", "inst1"))
    }

    @Test fun `login without app config fails`() {
        whenever(appConfigRepo.getByAppId("app1")).thenReturn(null)
        assertThatThrownBy { svc.loginWithProvider(ctx(), "google", LoginReq(idToken = "x")) }
            .isInstanceOf(ApiError::class.java)
            .extracting("errorCode").isEqualTo(ErrorCode.APP_CONFIG_MISSING)
    }

    @Test fun `refresh replay revokes all and fails`() {
        val row = AppRefreshTokenDocument().apply {
            id = "r1"; appId = "app1"; appUserId = "user1"; deviceSecretId = "ds1"
            revokedAt = Instant.now()  // 已撤销 = 重放
        }
        whenever(refreshRepo.findByHash("app1", "tok")).thenReturn(row)
        assertThatThrownBy { svc.refresh(ctx(), RefreshReq("tok")) }.isInstanceOf(ApiError::class.java)
        verify(refreshRepo).revokeByAppUser("app1", "user1")
    }
}
