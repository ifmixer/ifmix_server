package com.ifmix.core.api.infra.http.api

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import com.ifmix.core.api.entity.common.ActorTypes
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.auth.TokenExpiredException
import com.ifmix.core.api.infra.auth.VerifiedToken
import com.ifmix.core.api.infra.http.ActionContextFactory
import com.ifmix.core.api.infra.http.ActorRequirement
import com.ifmix.core.api.infra.http.ApiRequestBody
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ClientPlatform
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.LogContext
import com.ifmix.core.api.infra.http.RequestHeaders
import com.ifmix.core.api.infra.http.RequestMeta
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.springframework.mock.web.MockHttpServletRequest
import java.util.UUID

/**
 * [ActionContextFactory] 纯单测：mock [AuthJwtService]（不起 Spring context），
 * 真实 factory 实例 + [MockHttpServletRequest]（参照 WireCryptoTest / LogContextTest 风格）。
 *
 * 日志纪律自查：所有 ApiError message 为固定文案，绝不含 token 原文——
 * 用例中 token 值均带 "SECRET" 前缀以便暴露任何 token 泄漏（message 断言精确匹配）。
 */
class ActionContextFactoryTest {

    private val jwt = mock<AuthJwtService>()
    private val strict = ActionContextFactory(jwt, strict = true)
    private val relaxed = ActionContextFactory(jwt, strict = false)

    private val projectId = "ifmix-demo"
    private val iid = UUID.randomUUID()

    private fun request(): MockHttpServletRequest = MockHttpServletRequest().apply {
        // 走 LogContext 现有行为：模拟 filter 入口已 start（reqId 存于 request attribute）。
        LogContext.start(this)
    }

    private fun meta(
        token: String? = null,
        projectId: String? = null,
        reqId: String? = null,
        locale: String? = null,
        currency: String? = null,
        country: String? = null,
        clientPlatform: String? = null,
        appVersion: String? = null,
        otaVersion: String? = null,
        userTz: String? = null,
        deviceModel: String? = null,
        osVersion: String? = null,
    ) = RequestMeta(
        reqId = reqId,
        projectId = projectId,
        accessToken = token,
        locale = locale,
        currency = currency,
        country = country,
        userTz = userTz,
        appVersion = appVersion,
        otaVersion = otaVersion,
        clientPlatform = clientPlatform,
        deviceModel = deviceModel,
        osVersion = osVersion,
    )

    /** fromRpc 的 body 实参：meta 透传（原 meta 传参逻辑映射到 body.meta）。 */
    private fun body(meta: RequestMeta? = null) = ApiRequestBody<Any?>(meta)

    /** install token（type=5，无 sub）或 customer/manager token（sub 为合法 UUID）的可信 claims。 */
    private fun token(type: Int, installId: String? = null, actorId: String? = null) = VerifiedToken(
        actorId = actorId,
        projectId = projectId,
        actorType = if (type == AuthJwtService.TOKEN_TYPE_INSTALL) 0 else ActorTypes.CUSTOMER,
        tokenType = type,
        installId = installId,
    )

    @BeforeEach
    fun reset() {
        LogContext.clear()
        Mockito.clearInvocations(jwt)
    }

    // ===== 3.9 CUSTOMER 端点 token 裁决 =====

    @Test
    fun `customer endpoint without token throws UNAUTHORIZED authentication required`() {
        val ex = assertThrows<ApiError> {
            strict.fromRpc(request(), "demoGet", body = body(meta(projectId = projectId)), requireActorType = ActorRequirement.CUSTOMER)
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
        assertThat(ex.message).isEqualTo("authentication required")
    }

    @Test
    fun `customer endpoint with install token throws UNAUTHORIZED customer authentication required`() {
        Mockito.`when`(jwt.verify(any())).thenReturn(token(AuthJwtService.TOKEN_TYPE_INSTALL, installId = iid.toString()))
        val ex = assertThrows<ApiError> {
            strict.fromRpc(request(), "demoGet", body = body(meta("SECRET-install", projectId)), requireActorType = ActorRequirement.CUSTOMER)
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
        assertThat(ex.message).isEqualTo("customer authentication required")
        LogContext.clear()
    }

    @Test
    fun `customer endpoint with manager token throws FORBIDDEN`() {
        Mockito.`when`(jwt.verify(any())).thenReturn(
            VerifiedToken(
                actorId = UUID.randomUUID().toString(),
                projectId = projectId,
                actorType = ActorTypes.MANAGER,
                tokenType = AuthJwtService.TOKEN_TYPE_MANAGER,
            )
        )
        val ex = assertThrows<ApiError> {
            strict.fromRpc(request(), "demoGet", body = body(meta("SECRET-manager", projectId)), requireActorType = ActorRequirement.CUSTOMER)
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.FORBIDDEN)
        assertThat(ex.message).isEqualTo("actor type not allowed for this endpoint")
        LogContext.clear()
    }

    @Test
    fun `customer endpoint with customer token succeeds with actor fields`() {
        val cid = UUID.randomUUID()
        Mockito.`when`(jwt.verify(any())).thenReturn(
            VerifiedToken(actorId = cid.toString(), projectId = projectId, actorType = ActorTypes.CUSTOMER, sessionId = "s1")
        )
        val ctx = strict.fromRpc(request(), "demoGet", body = body(meta("SECRET-customer", projectId)), requireActorType = ActorRequirement.CUSTOMER)
        assertThat(ctx.actorId).isEqualTo(cid)
        assertThat(ctx.actorType).isEqualTo(ActorTypes.CUSTOMER)
        assertThat(ctx.anonymous).isEqualTo(false)
        assertThat(ctx.sessionId).isEqualTo("s1")
        assertThat(ctx.tokenType).isEqualTo(AuthJwtService.TOKEN_TYPE_CUSTOMER)
        assertThat(ctx.tokenInstallId).isNull()
        LogContext.clear()
    }

    // ===== token 校验失败路径 =====

    @Test
    fun `expired token throws TOKEN_EXPIRED`() {
        Mockito.`when`(jwt.verify(any())).thenThrow(TokenExpiredException())
        val ex = assertThrows<ApiError> {
            strict.fromRpc(request(), "demoGet", body = body(meta("SECRET-expired", projectId)), requireActorType = ActorRequirement.CUSTOMER)
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.TOKEN_EXPIRED)
        assertThat(ex.message).isEqualTo("access token expired")
        LogContext.clear()
    }

    @Test
    fun `signature verification failure throws UNAUTHORIZED without leaking token`() {
        Mockito.`when`(jwt.verify(any())).thenReturn(null)
        val ex = assertThrows<ApiError> {
            strict.fromRpc(request(), "demoGet", body = body(meta("SECRET-bogus", projectId)), requireActorType = ActorRequirement.CUSTOMER)
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
        assertThat(ex.message).isEqualTo("invalid token: signature verification failed") // 固定文案，不含 token 原文
        LogContext.clear()
    }

    @Test
    fun `aud mismatch with meta project id throws UNAUTHORIZED app mismatch`() {
        Mockito.`when`(jwt.verify(any())).thenReturn(
            VerifiedToken(actorId = UUID.randomUUID().toString(), projectId = "other-app", actorType = ActorTypes.CUSTOMER)
        )
        val ex = assertThrows<ApiError> {
            strict.fromRpc(request(), "demoGet", body = body(meta("SECRET-cross", projectId)), requireActorType = ActorRequirement.CUSTOMER)
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
        assertThat(ex.message).isEqualTo("invalid token: app mismatch")
        LogContext.clear()
    }

    @Test
    fun `non install token without valid subject throws UNAUTHORIZED missing or invalid subject`() {
        Mockito.`when`(jwt.verify(any())).thenReturn(
            VerifiedToken(actorId = "not-a-uuid", projectId = projectId, actorType = ActorTypes.CUSTOMER)
        )
        val ex = assertThrows<ApiError> {
            strict.fromRpc(request(), "demoGet", body = body(meta("SECRET-nosub", projectId)), requireActorType = ActorRequirement.CUSTOMER)
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
        assertThat(ex.message).isEqualTo("invalid token: missing or invalid subject")
        LogContext.clear()
    }

    @Test
    fun `bearer prefixed meta token throws UNAUTHORIZED protocol education message before verify`() {
        val ex = assertThrows<ApiError> {
            strict.fromRpc(request(), "demoGet", body = body(meta("Bearer SECRET", projectId)), requireActorType = ActorRequirement.CUSTOMER)
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
        assertThat(ex.message).isEqualTo("invalid token: send raw token in meta.accessToken")
        Mockito.verifyNoInteractions(jwt) // 协议错误在验签前拦截
        LogContext.clear()
    }

    // ===== install token 合法 =====

    @Test
    fun `install token on install-or-customer endpoint yields null actor with token install id and type`() {
        Mockito.`when`(jwt.verify(any())).thenReturn(token(AuthJwtService.TOKEN_TYPE_INSTALL, installId = iid.toString()))
        val ctx = strict.fromRpc(
            request(),
            "m_auth_customer_createAnonymous",
            isMutation = true,
            body = body(meta("SECRET-install", projectId)),
            requireActorType = ActorRequirement.INSTALL_OR_CUSTOMER,
        )
        assertThat(ctx.actorId).isNull()
        assertThat(ctx.tokenInstallId).isEqualTo(iid)
        assertThat(ctx.tokenType).isEqualTo(AuthJwtService.TOKEN_TYPE_INSTALL)
        assertThat(ctx.installId).isNull()
        assertThat(ctx.actionName).isEqualTo("m_auth_customer_createAnonymous")
        assertThat(ctx.isMutation).isEqualTo(true)
        assertThat(ctx.preferReader).isEqualTo(false)
        LogContext.clear()
    }

    @Test
    fun `install token without iid claim gives null token install id`() {
        Mockito.`when`(jwt.verify(any())).thenReturn(token(AuthJwtService.TOKEN_TYPE_INSTALL, installId = null))
        val ctx = relaxed.fromRpc(
            request(),
            "demoGet",
            body = body(meta("SECRET-install", projectId)),
            requireActorType = ActorRequirement.INSTALL_OR_CUSTOMER,
        )
        assertThat(ctx.tokenInstallId).isNull()
        assertThat(ctx.tokenType).isEqualTo(AuthJwtService.TOKEN_TYPE_INSTALL)
        LogContext.clear()
    }

    // ===== NONE / INSTALL_OR_CUSTOMER 端点 =====

    @Test
    fun `none and install-or-customer endpoints without token pass with null actor`() {
        for (req in listOf(ActorRequirement.NONE, ActorRequirement.INSTALL_OR_CUSTOMER)) {
            val ctx = strict.fromRpc(request(), "demoGet", body = body(meta(projectId = projectId)), requireActorType = req)
            assertThat(ctx.actorId).isNull()
            assertThat(ctx.tokenType).isNull()
            LogContext.clear()
        }
    }

    @Test
    fun `expired token on none endpoint still throws TOKEN_EXPIRED`() {
        // 带了 token 就校验（与 RequestParser 语义一致：无论端点是否要求登录，过期/无效 token 都抛）
        Mockito.`when`(jwt.verify(any())).thenThrow(TokenExpiredException())
        val ex = assertThrows<ApiError> {
            strict.fromRpc(request(), "demoGet", body = body(meta("SECRET-expired", projectId)))
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.TOKEN_EXPIRED)
        LogContext.clear()
    }

    // ===== projectId =====

    @Test
    fun `missing project id on requiring endpoint throws INVALID_REQUEST`() {
        val ex = assertThrows<ApiError> {
            relaxed.fromRpc(
                request(),
                "demoGet",
                body = body(meta(null, null)),
                requireActorType = ActorRequirement.NONE,
                requireProjectId = true,
            )
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
        assertThat(ex.message).isEqualTo("projectId is required")
        LogContext.clear()
    }

    @Test
    fun `invalid project id format throws INVALID_REQUEST`() {
        for (bad in listOf("IFMIX", "ab", "ifmix_demo", "Ifmix-demo")) {
            val ex = assertThrows<ApiError> {
                relaxed.fromRpc(request(), "demoGet", body = body(meta(null, bad)), requireActorType = ActorRequirement.NONE)
            }
            assertThat(ex.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
            assertThat(ex.message).isEqualTo("invalid projectId format")
            LogContext.clear()
        }
    }

    @Test
    fun `project id not required passes without meta project id`() {
        val ctx = relaxed.fromRpc(
            request(),
            "demoGet",
            body = body(meta(null, null)),
            requireActorType = ActorRequirement.NONE,
            requireProjectId = false,
        )
        assertThat(ctx.projectId).isNull()
        LogContext.clear()
    }

    // ===== locale / currency / country 软校验 =====

    @Test
    fun `strict mode rejects bad currency`() {
        val ex = assertThrows<ApiError> {
            strict.fromRpc(request(), "q_demo_todo_getById", body = body(meta(projectId = projectId, currency = "usd1")), requireActorType = ActorRequirement.NONE)
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
        assertThat(ex.message).isEqualTo("invalid meta.currency: invalid format")
        LogContext.clear()
    }

    @Test
    fun `relaxed mode drops bad currency to null`() {
        val ctx = relaxed.fromRpc(request(), "demoGet", body = body(meta(projectId = projectId, currency = "usd1")), requireActorType = ActorRequirement.NONE)
        assertThat(ctx.currency).isNull()
        LogContext.clear()
    }

    @Test
    fun `currency and country normalized to uppercase`() {
        val ctx = relaxed.fromRpc(
            request(),
            "demoGet",
            body = body(meta(projectId = projectId, currency = "usd", country = "cn")),
            requireActorType = ActorRequirement.NONE,
        )
        assertThat(ctx.currency).isEqualTo("USD")
        assertThat(ctx.country).isEqualTo("CN")
        LogContext.clear()
    }

    @Test
    fun `relaxed mode drops bad country to null`() {
        val ctx = relaxed.fromRpc(request(), "demoGet", body = body(meta(projectId = projectId, country = "cn1")), requireActorType = ActorRequirement.NONE)
        assertThat(ctx.country).isNull()
        LogContext.clear()
    }

    @Test
    fun `locale not in supported set yields null without throwing`() {
        for (v in listOf("ko", "ru-RU", "en-US")) {
            val ctx = relaxed.fromRpc(request(), "demoGet", body = body(meta(projectId = projectId, locale = v)), requireActorType = ActorRequirement.NONE)
            assertThat(ctx.locale).isEqualTo(if (v == "en-US") "en" else null)
            LogContext.clear()
        }
    }

    // ===== meta 独有字段 / 固定值 =====

    @Test
    fun `user tz device model os version pass through trimmed`() {
        val ctx = relaxed.fromRpc(
            request(),
            "m_demo_todo_updateOne",
            isMutation = true,
            body = body(meta(projectId = projectId, userTz = " Asia/Shanghai ", deviceModel = "Pixel 8 ", osVersion = " 15 ")),
            requireActorType = ActorRequirement.NONE,
        )
        assertThat(ctx.userTz).isEqualTo("Asia/Shanghai")
        assertThat(ctx.deviceModel).isEqualTo("Pixel 8")
        assertThat(ctx.osVersion).isEqualTo("15")
        assertThat(ctx.installId).isNull()
        assertThat(ctx.actionName).isEqualTo("m_demo_todo_updateOne")
        assertThat(ctx.isMutation).isEqualTo(true)
        assertThat(ctx.preferReader).isEqualTo(false)
        assertThat(ctx.readCache).isEqualTo(false)
        LogContext.clear()
    }

    @Test
    fun `query action gets prefer reader true and query cache`() {
        val ctx = relaxed.fromRpc(request(), "demoGet", body = body(meta(projectId = projectId)), requireActorType = ActorRequirement.NONE)
        assertThat(ctx.preferReader).isEqualTo(true)
        assertThat(ctx.readCache).isEqualTo(true)
        LogContext.clear()
    }

    @Test
    fun `app version and ota version passed through trimmed`() {
        val ctx = relaxed.fromRpc(
            request(),
            "demoGet",
            body = body(meta(projectId = projectId, appVersion = " 1.2.3 ", otaVersion = " 1-23-3 ")),
            requireActorType = ActorRequirement.NONE,
        )
        assertThat(ctx.appVersion).isEqualTo("1.2.3")
        assertThat(ctx.otaVersion).isEqualTo("1-23-3")
        LogContext.clear()
    }

    @Test
    fun `null meta is treated as empty meta`() {
        val ctx = relaxed.fromRpc(
            request(),
            "demoGet",
            body = body(null),
            requireActorType = ActorRequirement.NONE,
            requireProjectId = false,
        )
        assertThat(ctx.projectId).isNull()
        assertThat(ctx.actorId).isNull()
        LogContext.clear()
    }

    // ===== requestId =====

    @Test
    fun `meta req id takes priority over log context request id`() {
        val ctx = relaxed.fromRpc(request(), "demoGet", body = body(meta(projectId = projectId, reqId = "client-req-1")), requireActorType = ActorRequirement.NONE)
        assertThat(ctx.requestId).isEqualTo("client-req-1")
        LogContext.clear()
    }

    @Test
    fun `request id falls back to x req id channel via log context`() {
        val req = MockHttpServletRequest()
        req.addHeader(RequestHeaders.REQ_ID, "xrid-42")
        LogContext.start(req)
        val ctx = relaxed.fromRpc(req, "demoGet", body = body(meta(projectId = projectId)), requireActorType = ActorRequirement.NONE)
        assertThat(ctx.requestId).isEqualTo("xrid-42")
        LogContext.clear()
    }

    // ===== clientIp / botScore 来自真实 request =====

    @Test
    fun `client ip and bot score come from real request not meta`() {
        val req = MockHttpServletRequest().apply {
            addHeader("X-Forwarded-For", "9.9.9.9, 10.0.0.1")
            addHeader(RequestHeaders.CF_BOT_SCORE, " 42 ")
        }
        LogContext.start(req)
        val ctx = relaxed.fromRpc(req, "demoGet", body = body(meta(projectId = projectId, clientPlatform = "WEB")), requireActorType = ActorRequirement.NONE)
        assertThat(ctx.clientIp).isEqualTo("9.9.9.9")
        assertThat(ctx.botScore).isEqualTo(42)
        assertThat(ctx.clientPlatform).isEqualTo(ClientPlatform.WEB)
        LogContext.clear()
    }

    @Test
    fun `out of range bot score ignored and ip falls back to remote address`() {
        val req = MockHttpServletRequest().apply {
            addHeader(RequestHeaders.CF_BOT_SCORE, "0")
        }
        LogContext.start(req)
        val ctx = relaxed.fromRpc(req, "demoGet", body = body(meta(projectId = projectId)), requireActorType = ActorRequirement.NONE)
        assertThat(ctx.botScore).isNull()
        assertThat(ctx.clientIp).isEqualTo(req.remoteAddr)
        LogContext.clear()
    }

    @Test
    fun `invalid client platform rejected in strict mode`() {
        val ex = assertThrows<ApiError> {
            strict.fromRpc(request(), "q_demo_todo_getById", body = body(meta(projectId = projectId, clientPlatform = "palmos")), requireActorType = ActorRequirement.NONE)
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
        assertThat(ex.message).isEqualTo("invalid client platform: palmos")
        LogContext.clear()
    }

    @Test
    fun `invalid client platform dropped to null in relaxed mode`() {
        val ctx = relaxed.fromRpc(request(), "q_demo_todo_getById", body = body(meta(projectId = projectId, clientPlatform = "palmos")), requireActorType = ActorRequirement.NONE)
        assertThat(ctx.clientPlatform).isNull()
        LogContext.clear()
    }

    // ===== Jackson 向前兼容 =====

    @Test
    fun `unknown meta fields are ignored for forward compatibility`() {
        val json = """{"meta":{"projectId":"$projectId","deviceModel":"X","futureField":123}}"""
        val body = tools.jackson.module.kotlin.jacksonObjectMapper()
            .readValue(json, com.ifmix.core.api.infra.http.ApiRequestBody::class.java)
        val m = body.meta
        assertThat(m).isNotNull()
        val ctx = relaxed.fromRpc(request(), "demoGet", body = ApiRequestBody<Any?>(m), requireActorType = ActorRequirement.NONE)
        assertThat(ctx.deviceModel).isEqualTo("X")
        LogContext.clear()
    }

    // ===== meta 透传：ctx.meta 持有 raw 实例 =====

    @Test
    fun `ctx meta is the raw body meta instance`() {
        val m = meta(projectId = projectId, userTz = "Asia/Shanghai")
        val ctx = relaxed.fromRpc(request(), "demoGet", body = body(m), requireActorType = ActorRequirement.NONE)
        assertThat(ctx.meta).isSameInstanceAs(m)
        LogContext.clear()
    }

    @Test
    fun `null body meta gives empty RequestMeta on ctx`() {
        val ctx = relaxed.fromRpc(
            request(),
            "demoGet",
            body = body(null),
            requireActorType = ActorRequirement.NONE,
            requireProjectId = false,
        )
        assertThat(ctx.meta.projectId).isNull()
        LogContext.clear()
    }

    // ===== 前缀 ⇔ 读写一致性（实施单 §1.2-1：显式 isMutation 与 actionName 前缀不一致 → IllegalStateException）=====

    @Test
    fun `explicit isMutation mismatch with m_ prefix throws IllegalStateException`() {
        assertThrows<IllegalStateException> {
            strict.fromRpc(request(), "m_demo_todo_createOne", isMutation = false, body = body(meta(projectId = projectId)))
        }
    }

    @Test
    fun `explicit isMutation mismatch with q_ prefix throws IllegalStateException`() {
        assertThrows<IllegalStateException> {
            strict.fromRpc(request(), "q_demo_todo_getById", isMutation = true, body = body(meta(projectId = projectId)))
        }
    }

    @Test
    fun `omitted isMutation is derived from action name prefix`() {
        val ctx = relaxed.fromRpc(request(), "q_demo_todo_getById", body = body(meta(projectId = projectId)), requireActorType = ActorRequirement.NONE)
        assertThat(ctx.isMutation).isEqualTo(false)
        assertThat(ctx.preferReader).isEqualTo(true)
        LogContext.clear()
        val mctx = relaxed.fromRpc(request(), "m_demo_todo_createOne", body = body(meta(projectId = projectId)), requireActorType = ActorRequirement.NONE)
        assertThat(mctx.isMutation).isEqualTo(true)
        assertThat(mctx.preferReader).isEqualTo(false)
        LogContext.clear()
    }
}
