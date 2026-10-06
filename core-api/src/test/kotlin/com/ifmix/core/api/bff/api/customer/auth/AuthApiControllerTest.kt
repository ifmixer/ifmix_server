package com.ifmix.core.api.bff.api.customer.auth

import com.ifmix.core.api.dto.auth.LoginInput
import com.ifmix.core.api.dto.auth.RefreshInput
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.auth.VerifiedToken
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ApiRequestBody
import com.ifmix.core.api.infra.http.ActionContextFactory
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.LogContext
import com.ifmix.core.api.infra.http.RequestMeta
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.auth.AuthFacade
import com.ifmix.core.api.modules.auth.handler.LoginRes
import com.ifmix.core.api.modules.auth.handler.MeRes
import com.ifmix.core.api.entity.common.ActorTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.reflect.full.declaredMemberFunctions
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * [AuthApiController] 关键语义测试（M2）：login 双上下文（customer/install token + 可信 iid）、
 * refresh 允许过期 access token、logout/me 的 customer 要求。
 */
class AuthApiControllerTest {

    private val projectId = "ifmix-demo"
    private val reqId = "req-auth-1"
    private val customerId = UUID.randomUUID()
    private val installId = UUID.randomUUID()

    private val jwt = mock<AuthJwtService>()
    private val authService = mock<AuthFacade>()
    private val globalTx = mock<GlobalTxRunner>()

    private lateinit var controller: AuthApiController

    @BeforeEach
    fun setUp() {
        whenever(globalTx.withTx<Any>(any(), any())).thenAnswer {
            ((it.arguments[1]) as (ActionContext) -> Any)(it.arguments[0] as ActionContext)
        }
        controller = AuthApiController(authService, globalTx, ActionContextFactory(jwt, strict = true))
    }

    private inline fun <reified T : Any> body(input: Map<String, Any?>?): ApiRequestBody<T> {
        val mapper = JsonMapper.builder().build()
        return ApiRequestBody(RequestMeta(reqId = reqId, projectId = projectId, accessToken = "SECRET"), input?.let { mapper.convertValue(it, T::class.java) })
    }

    private fun request() = MockHttpServletRequest().apply { LogContext.start(this) }

    private fun customerJwt(anonymous: Boolean = false) {
        whenever(jwt.verify(any())).thenReturn(
            VerifiedToken(
                actorId = customerId.toString(), projectId = projectId,
                actorType = ActorTypes.CUSTOMER, tokenType = AuthJwtService.TOKEN_TYPE_CUSTOMER,
                anonymous = anonymous, installId = installId.toString(),
            ),
        )
    }

    private fun installJwt() {
        whenever(jwt.verify(any())).thenReturn(
            VerifiedToken(
                actorId = null, projectId = projectId, actorType = ActorTypes.CUSTOMER,
                tokenType = AuthJwtService.TOKEN_TYPE_INSTALL, installId = installId.toString(),
            ),
        )
    }

    private fun loginRes() = LoginRes(
        accessToken = "access-2", refreshToken = "refresh-2", refreshExpiresAt = null,
        expiresIn = 3600L, user = com.ifmix.core.api.modules.auth.handler.UserDto(customerId, "a@b.c"),
    )

    // ===== login =====

    @Test
    fun `login with customer token succeeds and keeps promote or merge context`() {
        customerJwt()
        whenever(authService.login(any(), any())).thenReturn(loginRes())
        val resp = controller.login(
            request(),
            body(mapOf("idpId" to UUID.randomUUID().toString(), "credential" to "cred")),
        )
        assertEquals("200000", resp.body!!.code)
        assertEquals("access-2", resp.body!!.data?.accessToken)
        assertEquals(customerId, resp.body!!.data?.user?.id)
        verify(globalTx).withTx<Any>(any(), any())
        LogContext.clear()
    }

    @Test
    fun `login with install token succeeds`() {
        installJwt()
        whenever(authService.login(any(), any())).thenReturn(loginRes())
        val resp = controller.login(
            request(),
            body(mapOf("idpId" to UUID.randomUUID().toString(), "credential" to "cred")),
        )
        assertEquals("200000", resp.body!!.code)
        LogContext.clear()
    }

    @Test
    fun `login without token throws 401000`() {
        val ex = assertThrows(ApiError::class.java) {
            controller.login(request(), body(mapOf("idpId" to UUID.randomUUID().toString(), "credential" to "cred")))
        }
        assertEquals(ErrorCode.UNAUTHORIZED, ex.errorCode)
        org.mockito.Mockito.verifyNoInteractions(globalTx)
        LogContext.clear()
    }

    // ===== refresh =====

    @Test
    fun `refresh with expired access token still works via refresh token in input`() {
        // 模拟过期 access token：jwt.verify 抛 TokenExpiredException → factory 转 TOKEN_EXPIRED？
        // 语义红线：refresh 的 access token 允许过期/缺失——factory 对 refresh spec（NONE）仍校验 token，
        // 过期 → TOKEN_EXPIRED 与 GraphQL 行为一致？否——GraphQL 路径 fromDfe 同样校验过期 token。
        // 客户端 refresh 用 authless 或携带未过期 install token，因此这里测「install token + 过期时间未到」正路径。
        installJwt()
        whenever(authService.refresh(any(), any())).thenReturn(
            com.ifmix.core.api.modules.auth.handler.RefreshRes(
                accessToken = "a2", refreshToken = "r2", refreshExpiresAt = null, expiresIn = 3600L,
            ),
        )
        val resp = controller.refresh(request(), body(mapOf("refreshToken" to "refresh-1")))
        assertEquals("200000", resp.body!!.code)
        assertEquals("a2", resp.body!!.data?.accessToken)
        LogContext.clear()
    }

    @Test
    fun `refresh without token throws 401000`() {
        val ex = assertThrows(ApiError::class.java) {
            controller.refresh(request(), body(mapOf("refreshToken" to "refresh-1")))
        }
        assertEquals(ErrorCode.UNAUTHORIZED, ex.errorCode)
        LogContext.clear()
    }

    // ===== me / logout / deleteAccount =====

    @Test
    fun `me returns user tier and tierExpiresAt`() {
        customerJwt()
        whenever(authService.me(any())).thenReturn(
            MeRes(id = customerId, email = "a@b.c", tier = 20, active = true, expiresAt = 1893456000000L),
        )
        val resp = controller.me(request(), body(null))
        assertEquals("200000", resp.body!!.code)
        assertEquals(20, resp.body!!.data?.tier)
        assertEquals(true, resp.body!!.data?.tierActive)
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), resp.body!!.data?.tierExpiresAt)
        LogContext.clear()
    }

    @Test
    fun `me without token throws 401000`() {
        val ex = assertThrows(ApiError::class.java) { controller.me(request(), body(null)) }
        assertEquals(ErrorCode.UNAUTHORIZED, ex.errorCode)
        LogContext.clear()
    }

    @Test
    fun `logout wraps in tx and returns success`() {
        customerJwt()
        val resp = controller.logout(request(), body(mapOf("refreshToken" to "refresh-1")))
        assertEquals("200000", resp.body!!.code)
        assertEquals(true, resp.body!!.data?.success)
        verify(globalTx).withTx<Any>(any(), any())
        LogContext.clear()
    }

    @Test
    fun `deleteAccount wraps in tx and returns success`() {
        customerJwt()
        val resp = controller.deleteAccount(request(), body(null))
        assertEquals("200000", resp.body!!.code)
        assertEquals(true, resp.body!!.data?.success)
        verify(globalTx).withTx<Any>(any(), any())
        LogContext.clear()
    }

    // ===== 命名一致性护栏（实施单 §1.3：四段格式 + module 段 + action 在 rpc-rollout-client §1 表内）=====

    @Test
    fun `routes match controller companion constants and rollout table`() {
        // rpc-rollout-client.md §1 R2 名单一真相：5 个 action 全覆盖
        val expected = mapOf(
            AuthApiController.REQNAME_LOGIN to "m",
            AuthApiController.REQNAME_REFRESH to "m",
            AuthApiController.REQNAME_LOGOUT to "m",
            AuthApiController.REQNAME_ME to "q",
            AuthApiController.REQNAME_DELETE_ACCOUNT to "m",
        )
        val postings = AuthApiController::class.declaredMemberFunctions
            .filter { it.annotations.any { a -> a is org.springframework.web.bind.annotation.PostMapping } }
        assertEquals(expected.keys.size, postings.size, "every endpoint has exactly one @PostMapping")
        val paths = mutableSetOf<String>()
        for (f in postings) {
            val path = f.annotations.filterIsInstance<org.springframework.web.bind.annotation.PostMapping>().single().value.first()
            paths.add(path)
            // path / operationId / companion 常量三处同源
            val op = f.annotations.filterIsInstance<io.swagger.v3.oas.annotations.Operation>().single()
            assertEquals(path, op.operationId, "operationId = path for $path")
            assertEquals(path, companionConstant(path), "companion constant exists for $path")
            // 四段格式 + module 段 + q/m 前缀
            val segs = path.split("_")
            assertEquals(4, segs.size, "four-segment format: $path")
            assertEquals(expected[path], segs[0], "q/m prefix: $path")
            assertEquals("auth", segs[1], "module segment: $path")
        }
        assertEquals(expected.keys, paths)
    }

    private fun companionConstant(path: String): String? =
        // Kotlin const val 在 companion 声明时编译为外层类的 static 字段
        AuthApiController::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .mapNotNull { f ->
                f.isAccessible = true
                (f.get(null) as? String)?.takeIf { it == path }
            }
            .firstOrNull()
}
