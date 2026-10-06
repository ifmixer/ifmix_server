package com.ifmix.core.api.bff.api.customer.auth

import com.ifmix.core.api.dto.auth.install.AttestExistingInput
import com.ifmix.core.api.dto.auth.install.CreateInstallInput
import com.ifmix.core.api.infra.attest.AttestGuard
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.auth.VerifiedToken
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ActionContextFactory
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ApiRequestBody
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.LogContext
import com.ifmix.core.api.infra.http.RequestMeta
import com.ifmix.core.api.infra.ratelimit.RateLimitProperties
import com.ifmix.core.api.infra.ratelimit.RateLimiter
import com.ifmix.core.api.infra.ratelimit.RateLimitResult
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.auth.install.InstallFacade
import com.ifmix.core.api.modules.project.ProjectServerConfigFacade
import com.ifmix.core.api.entity.common.ActorTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.reflect.full.declaredMemberFunctions
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * [InstallApiController] 关键语义测试（M2）：token 要求、IP 限流（429000 + retryAfterSec）、
 * challenge 关闭路径。attest 决策矩阵（ENFORCE/OBSERVE × proof 组合）逻辑自 InstallFetcher
 * 逐行平移，矩阵级覆盖由原 InstallFetcherAttestTest 语义在 e2e/M5 review 时补齐（见试点报告）。
 */
class InstallApiControllerTest {

    private val projectId = "ifmix-demo"
    private val reqId = "req-install-1"

    private val jwt = mock<AuthJwtService>()
    private val installFacade = mock<InstallFacade>()
    private val globalTx = mock<GlobalTxRunner>()
    private val rateLimiter = mock<RateLimiter>()
    private val rlProps = RateLimitProperties()
    private val attestGuard = mock<AttestGuard>()
    private val serverConfigFacade = mock<ProjectServerConfigFacade>()

    private lateinit var controller: InstallApiController

    @BeforeEach
    fun setUp() {
        whenever(globalTx.withTx<Any>(any(), any())).thenAnswer {
            ((it.arguments[1]) as (ActionContext) -> Any)(it.arguments[0] as ActionContext)
        }
        whenever(rateLimiter.check(any(), any(), any())).thenReturn(RateLimitResult.Allowed)
        controller = InstallApiController(
            installFacade, globalTx, ActionContextFactory(jwt, strict = true),
            rateLimiter, rlProps, attestGuard, serverConfigFacade,
        )
    }

    private fun meta(token: String?) = RequestMeta(reqId = reqId, projectId = projectId, accessToken = token)

    private inline fun <reified T : Any> body(meta: RequestMeta?, input: Map<String, Any?>?): ApiRequestBody<T> {
        val mapper = JsonMapper.builder().build()
        return ApiRequestBody(meta, input?.let { mapper.convertValue(it, T::class.java) })
    }

    private fun request() = MockHttpServletRequest().apply { LogContext.start(this) }

    private fun customerJwt() {
        whenever(jwt.verify(any())).thenReturn(
            VerifiedToken(
                actorId = UUID.randomUUID().toString(), projectId = projectId,
                actorType = ActorTypes.CUSTOMER, tokenType = AuthJwtService.TOKEN_TYPE_CUSTOMER,
            ),
        )
    }

    private fun installJwt() {
        whenever(jwt.verify(any())).thenReturn(
            VerifiedToken(
                actorId = null, projectId = projectId, actorType = ActorTypes.CUSTOMER,
                tokenType = AuthJwtService.TOKEN_TYPE_INSTALL, installId = UUID.randomUUID().toString(),
            ),
        )
    }

    // ===== attestExisting：严格只认 installToken =====

    @Test
    fun `attestExisting without token throws 401000`() {
        val ex = assertThrows(ApiError::class.java) {
            controller.attestExisting(request(), body(meta(null), mapOf("proof" to mapOf("provider" to 110))))
        }
        assertEquals(ErrorCode.UNAUTHORIZED, ex.errorCode)
        verifyNoInteractions(rateLimiter, globalTx)
        LogContext.clear()
    }

    @Test
    fun `attestExisting with customer token throws 401000`() {
        customerJwt()
        val ex = assertThrows(ApiError::class.java) {
            controller.attestExisting(request(), body(meta("SECRET"), mapOf("proof" to mapOf("provider" to 110))))
        }
        assertEquals(ErrorCode.UNAUTHORIZED, ex.errorCode)
        LogContext.clear()
    }

    @Test
    fun `attestExisting with install token and attest disabled returns 30`() {
        installJwt()
        whenever(attestGuard.isAttestationEnabled(projectId)).thenReturn(false)
        val resp = controller.attestExisting(request(), body(meta("SECRET"), mapOf("proof" to mapOf("provider" to 110))))
        assertEquals("200000", resp.body!!.code)
        assertEquals(30, resp.body!!.data?.attestationStatus)
        LogContext.clear()
    }

    // ===== createInstall：IP 限流 429000（retryAfterSec 必带）=====

    @Test
    fun `createInstall ip rate limited returns 429000 with retryAfterSec`() {
        customerJwt()
        whenever(rateLimiter.check(eq(com.ifmix.core.api.infra.ratelimit.Window.MINUTE), any(), any()))
            .thenReturn(RateLimitResult.Limited(retryAfterSec = 37))
        val ex = assertThrows(ApiError::class.java) {
            controller.createInstall(request(), body(meta("SECRET"), mapOf("storeType" to 10)))
        }
        assertEquals(ErrorCode.RATE_LIMITED, ex.errorCode)
        assertEquals(37L, ex.retryAfterSec)
        verifyNoInteractions(globalTx)
        LogContext.clear()
    }

    @Test
    fun `createInstall no-proof passes guard and creates in tx`() {
        customerJwt()
        val installId = UUID.randomUUID()
        whenever(attestGuard.parseProofInput(isNull(), isNull())).thenReturn(null)
        whenever(attestGuard.verifyProof(any(), isNull(), isNull()))
            .thenReturn(AttestGuard.Verification.NoProof)
        whenever(attestGuard.decideCreateInstall(AttestGuard.Verification.NoProof, null))
            .thenReturn(AttestGuard.CreateInstallDecision.NOT_ATTEMPTED)
        whenever(
            installFacade.createInstallWithProof(any(), anyOrNull(), eq(10), isNull()),
        ).thenReturn(com.ifmix.core.api.modules.auth.install.handler.InstallAggHandler.CreateInstallRes(installId, "token-1"))
        whenever(serverConfigFacade.findAttestConfig(projectId)).thenReturn(null)

        val resp = controller.createInstall(request(), body(meta("SECRET"), mapOf("storeType" to 10)))
        assertEquals("200000", resp.body!!.code)
        assertEquals(installId, resp.body!!.data?.installId)
        assertEquals(30, resp.body!!.data?.attestationStatus)
        verify(globalTx).withTx<Any>(any(), any())
        LogContext.clear()
    }

    // ===== createAttestChallenge：关闭路径 =====

    @Test
    fun `createAttestChallenge disabled returns enabled=false`() {
        whenever(attestGuard.isChallengeEnabled(eq(projectId), isNull())).thenReturn(false)
        val resp = controller.createAttestChallenge(request(), body(meta(null), mapOf<String, Any?>()))
        assertEquals("200000", resp.body!!.code)
        assertEquals(false, resp.body!!.data?.enabled)
        assertEquals(null, resp.body!!.data?.challenge)
        LogContext.clear()
    }

    // ===== 命名一致性护栏（实施单 §1.3：四段格式 + module 段 + action 在 rpc-rollout-client §1 表内；
    // install 的 action 名 module 段是 auth 表里的 install 段，包在 ...customer.auth 下，以 action 名第 2 段为准）=====

    @Test
    fun `routes one-to-one with controller companion constants`() {
        // rpc-rollout-client.md §1 R2：install 5 action 名单一真相
        val expected = setOf(
            "m_auth_install_create", "m_auth_install_updateOne", "m_auth_install_attest",
            "m_auth_install_recover", "m_auth_install_createAttestChallenge",
        )
        val postings = InstallApiController::class.declaredMemberFunctions
            .filter { it.annotations.any { a -> a is org.springframework.web.bind.annotation.PostMapping } }
        assertEquals(5, postings.size)
        val names = postings.map { f ->
            val path = f.annotations.filterIsInstance<org.springframework.web.bind.annotation.PostMapping>().single().value.first()
            val operationId = f.annotations.filterIsInstance<io.swagger.v3.oas.annotations.Operation>().single().operationId
            assertEquals(path, operationId, "path must equal operationId: " + path)
            path
        }
        assertEquals(expected, names.toSet())
        names.forEach { n ->
            val segs = n.split("_")
            assertEquals(4, segs.size, "four-segment format: " + n)
            assertTrue(segs[0] == "m", "all install actions are mutations: " + n)
            assertTrue(segs[1] == "auth", "module segment: " + n)
            assertTrue(segs[2] == "install", "resource segment (install 段在 auth 包下，以 action 名第 2 段为准): " + n)
        }
        // companion 常量与路由 path 同源
        assertEquals("m_auth_install_create", InstallApiController.ACTION_CREATE_INSTALL)
        assertEquals("m_auth_install_createAttestChallenge", InstallApiController.ACTION_CREATE_ATTEST_CHALLENGE)
    }
}

private fun assertTrue(b: Boolean, msg: String? = null) = org.junit.jupiter.api.Assertions.assertTrue(b, msg)
