package com.ifmix.core.api.modules.auth.handler

import com.ifmix.core.api.entity.auth.RefreshToken
import com.ifmix.core.api.entity.common.ActorTypes
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.auth.ProviderVerifier
import com.ifmix.core.api.modules.auth.repo.AuthIdentityIdpRelationRepository
import com.ifmix.core.api.modules.auth.repo.AuthIdentityRepository
import com.ifmix.core.api.modules.auth.repo.IdpIdentityRepository
import com.ifmix.core.api.modules.auth.repo.IdpRepository
import com.ifmix.core.api.modules.auth.repo.ProjectToIdpRelationRepository
import com.ifmix.core.api.modules.auth.repo.RefreshTokenRepository
import com.ifmix.core.api.modules.customer.handler.CustomerMergeHandler
import com.ifmix.core.api.modules.customer.repo.CustomerRepository
import com.ifmix.core.api.modules.install.InstallFacade
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

class AuthRefreshInstallTest {
    @Test
    fun `refresh stamps request install iid into new access token`() {
        val jwt = mock<AuthJwtService>()
        val refreshTokenRepo = mock<RefreshTokenRepository>()
        val customerRepo = mock<CustomerRepository>()
        val customer = mock<com.ifmix.core.api.entity.customer.Customer>()
        val oldToken = mock<RefreshToken>()
        val projectId = "antique"
        val actorId = UUID.randomUUID()
        val oldTokenId = UUID.randomUUID()
        val installId = UUID.randomUUID()
        val mc = ModuleCtx(
            action = ActionContext(projectId = projectId, tokenType = AuthJwtService.TOKEN_TYPE_INSTALL, tokenInstallId = installId),
            sql = mock<KSqlClient>(),
        )

        whenever(oldToken.id).thenReturn(oldTokenId)
        whenever(oldToken.actorId).thenReturn(actorId)
        whenever(oldToken.actorType).thenReturn(ActorTypes.CUSTOMER)
        whenever(customer.anonymous).thenReturn(true)
        whenever(customerRepo.findById(mc, projectId, actorId)).thenReturn(customer)
        whenever(refreshTokenRepo.findValidByHash(eq(mc), eq(projectId), any())).thenReturn(oldToken)
        whenever(jwt.signAccess(any(), any(), any(), any(), any(), anyOrNull())).thenReturn("new-access")

        val handler = AuthAggHandler(
            verifiers = emptyMap<String, ProviderVerifier>(),
            jwt = jwt,
            idpRepo = mock<IdpRepository>(),
            idpIdentityRepo = mock<IdpIdentityRepository>(),
            projectToIdpRepo = mock<ProjectToIdpRelationRepository>(),
            authIdentityRepo = mock<AuthIdentityRepository>(),
            relationRepo = mock<AuthIdentityIdpRelationRepository>(),
            refreshTokenRepo = refreshTokenRepo,
            customerRepo = customerRepo,
            mergeHandler = mock<CustomerMergeHandler>(),
            installFacade = mock<InstallFacade>(),
            events = mock<ApplicationEventPublisher>(),
            accessTtlSec = 900,
        )

        handler.refresh(mc, RefreshReq("legacy-refresh"))

        val tokenCaptor = argumentCaptor<RefreshToken>()
        verify(refreshTokenRepo).save(eq(mc), tokenCaptor.capture())
        org.assertj.core.api.Assertions.assertThat(tokenCaptor.firstValue.expiresAt).isNull()
        verify(jwt).signAccess(
            eq(actorId.toString()),
            eq(ActorTypes.CUSTOMER),
            eq(projectId),
            any(),
            eq(true),
            eq(installId.toString()),
        )
    }

    @Test
    fun `refresh binds customer actor to install when refreshing`() {
        val jwt = mock<AuthJwtService>()
        val refreshTokenRepo = mock<RefreshTokenRepository>()
        val customerRepo = mock<CustomerRepository>()
        val installFacade = mock<InstallFacade>()
        val customer = mock<com.ifmix.core.api.entity.customer.Customer>()
        val oldToken = mock<RefreshToken>()
        val projectId = "antique"
        val actorId = UUID.randomUUID()
        val installId = UUID.randomUUID()
        val action = ActionContext(projectId = projectId, tokenType = AuthJwtService.TOKEN_TYPE_INSTALL, tokenInstallId = installId)
        val mc = ModuleCtx(action = action, sql = mock<KSqlClient>())

        whenever(oldToken.id).thenReturn(UUID.randomUUID())
        whenever(oldToken.actorId).thenReturn(actorId)
        whenever(oldToken.actorType).thenReturn(ActorTypes.CUSTOMER)
        whenever(customer.anonymous).thenReturn(true)
        whenever(customerRepo.findById(mc, projectId, actorId)).thenReturn(customer)
        whenever(refreshTokenRepo.findValidByHash(eq(mc), eq(projectId), any())).thenReturn(oldToken)
        whenever(jwt.signAccess(any(), any(), any(), any(), any(), anyOrNull())).thenReturn("new-access")

        val handler = AuthAggHandler(
            verifiers = emptyMap<String, ProviderVerifier>(),
            jwt = jwt,
            idpRepo = mock<IdpRepository>(),
            idpIdentityRepo = mock<IdpIdentityRepository>(),
            projectToIdpRepo = mock<ProjectToIdpRelationRepository>(),
            authIdentityRepo = mock<AuthIdentityRepository>(),
            relationRepo = mock<AuthIdentityIdpRelationRepository>(),
            refreshTokenRepo = refreshTokenRepo,
            customerRepo = customerRepo,
            mergeHandler = mock<CustomerMergeHandler>(),
            installFacade = installFacade,
            events = mock<ApplicationEventPublisher>(),
            accessTtlSec = 900,
        )

        handler.refresh(mc, RefreshReq("legacy-refresh"))

        // refresh 续期时补绑 actor↔install（bind 幂等：未绑则绑，已绑则 NoOp）。
        verify(installFacade).bind(eq(action), eq(installId), eq(actorId))
    }

    @Test
    fun `createAnonymous creates customer refresh token and binds install in one flow`() {
        val jwt = mock<AuthJwtService>()
        val refreshTokenRepo = mock<RefreshTokenRepository>()
        val customerRepo = mock<CustomerRepository>()
        val installFacade = mock<InstallFacade>()
        val projectId = "antique"
        val customerId = UUID.randomUUID()
        val installId = UUID.randomUUID()
        val action = ActionContext(
            projectId = projectId,
            tokenType = AuthJwtService.TOKEN_TYPE_INSTALL,
            tokenInstallId = installId,
        )
        val mc = ModuleCtx(action = action, sql = mock<KSqlClient>())

        whenever(customerRepo.createCustomer(mc, projectId)).thenReturn(customerId)
        whenever(jwt.signAccess(any(), any(), any(), any(), any(), anyOrNull())).thenReturn("access")

        val handler = AuthAggHandler(
            verifiers = emptyMap<String, ProviderVerifier>(),
            jwt = jwt,
            idpRepo = mock<IdpRepository>(),
            idpIdentityRepo = mock<IdpIdentityRepository>(),
            projectToIdpRepo = mock<ProjectToIdpRelationRepository>(),
            authIdentityRepo = mock<AuthIdentityRepository>(),
            relationRepo = mock<AuthIdentityIdpRelationRepository>(),
            refreshTokenRepo = refreshTokenRepo,
            customerRepo = customerRepo,
            mergeHandler = mock<CustomerMergeHandler>(),
            installFacade = installFacade,
            events = mock<ApplicationEventPublisher>(),
            accessTtlSec = 900,
        )

        val res = handler.createAnonymousCustomer(mc)

        org.assertj.core.api.Assertions.assertThat(res.customerId).isEqualTo(customerId)
        // customer + refresh token + bind + 含 iid 的 access token
        verify(customerRepo).createCustomer(mc, projectId)
        val tokenCaptor = argumentCaptor<RefreshToken>()
        verify(refreshTokenRepo).save(eq(mc), tokenCaptor.capture())
        org.assertj.core.api.Assertions.assertThat(tokenCaptor.firstValue.expiresAt).isNull()
        verify(installFacade).bind(eq(action), eq(installId), eq(customerId))
        verify(jwt).signAccess(
            eq(customerId.toString()),
            eq(ActorTypes.CUSTOMER),
            eq(projectId),
            any(),
            eq(true),
            eq(installId.toString()),
        )
    }

    @Test
    fun `logout without iid remains compatible and skips install unbind`() {
        val jwt = mock<AuthJwtService>()
        val refreshTokenRepo = mock<RefreshTokenRepository>()
        val installFacade = mock<InstallFacade>()
        val actorId = UUID.randomUUID()
        val mc = ModuleCtx(
            action = ActionContext(projectId = "antique", actorId = actorId, tokenInstallId = null),
            sql = mock<KSqlClient>(),
        )

        val handler = AuthAggHandler(
            verifiers = emptyMap<String, ProviderVerifier>(),
            jwt = jwt,
            idpRepo = mock<IdpRepository>(),
            idpIdentityRepo = mock<IdpIdentityRepository>(),
            projectToIdpRepo = mock<ProjectToIdpRelationRepository>(),
            authIdentityRepo = mock<AuthIdentityRepository>(),
            relationRepo = mock<AuthIdentityIdpRelationRepository>(),
            refreshTokenRepo = refreshTokenRepo,
            customerRepo = mock<CustomerRepository>(),
            mergeHandler = mock<CustomerMergeHandler>(),
            installFacade = installFacade,
            events = mock<ApplicationEventPublisher>(),
            accessTtlSec = 900,
        )

        val result = handler.logout(mc, LogoutReq("legacy-refresh"))

        org.assertj.core.api.Assertions.assertThat(result.ok).isTrue()
        org.mockito.kotlin.verifyNoInteractions(installFacade)
    }
}
