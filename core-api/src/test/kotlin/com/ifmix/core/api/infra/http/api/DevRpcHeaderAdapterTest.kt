package com.ifmix.core.api.infra.http.api

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.auth.VerifiedToken
import com.ifmix.core.api.infra.http.ActionContextFactory
import com.ifmix.core.api.infra.http.ActorRequirement
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.DevRpcHeaderAdapter
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.LogContext
import com.ifmix.core.api.infra.http.RequestHeaders
import com.ifmix.core.api.infra.http.RequestMeta
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.springframework.mock.web.MockHttpServletRequest
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

/**
 * [DevRpcHeaderAdapter] 单测 + [ActionContextFactory] 集成用例（dev 态 header 合并单点）。
 *
 * 凭证信源契约：body.meta.accessToken > Authorization header；x-req-meta 内的 accessToken 一律忽略
 * （该 header 契约不含凭证）。token 值均带 "SECRET" 前缀以便暴露泄漏（与 ActionContextFactoryTest 同纪律）。
 */
class DevRpcHeaderAdapterTest {

    private val adapter = DevRpcHeaderAdapter(jacksonObjectMapper())

    private fun request(vararg headers: Pair<String, String>): MockHttpServletRequest =
        MockHttpServletRequest().apply { headers.forEach { (k, v) -> addHeader(k, v) } }

    // ===== merge：header 缺失路径 =====

    @Test
    fun `no dev headers returns body meta as is`() {
        val bodyMeta = RequestMeta(projectId = "ifmix-demo", accessToken = "SECRET-body")
        val merged = adapter.merge(request(), bodyMeta)
        assertThat(merged).isSameInstanceAs(bodyMeta)
    }

    @Test
    fun `no dev headers and null body meta gives empty meta`() {
        val merged = adapter.merge(request(), null)
        assertThat(merged.projectId).isNull()
        assertThat(merged.accessToken).isNull()
    }

    // ===== merge：x-req-meta 底座 + body 字段级优先 =====

    @Test
    fun `x req meta header is base and body fields win per field`() {
        val req = request(
            RequestHeaders.DEV_REQ_META to
                """{"projectId":"ifmix-header","locale":"en","currency":"USD","deviceModel":"Pixel"}""",
        )
        val merged = adapter.merge(req, RequestMeta(projectId = "ifmix-demo", locale = "zh-CN"))
        // body 提供的字段覆盖 header
        assertThat(merged.projectId).isEqualTo("ifmix-demo")
        assertThat(merged.locale).isEqualTo("zh-CN")
        // body 未提供的字段回落 header 底座
        assertThat(merged.currency).isEqualTo("USD")
        assertThat(merged.deviceModel).isEqualTo("Pixel")
    }

    @Test
    fun `x req meta header alone fills meta`() {
        val req = request(
            RequestHeaders.DEV_REQ_META to """{"projectId":"ifmix-demo","locale":"en"}""",
        )
        val merged = adapter.merge(req, null)
        assertThat(merged.projectId).isEqualTo("ifmix-demo")
        assertThat(merged.locale).isEqualTo("en")
    }

    @Test
    fun `blank body field does not clobber header base`() {
        val req = request(RequestHeaders.DEV_REQ_META to """{"projectId":"ifmix-demo"}""")
        val merged = adapter.merge(req, RequestMeta(locale = "  "))
        assertThat(merged.projectId).isEqualTo("ifmix-demo")
        assertThat(merged.locale).isNull()
    }

    @Test
    fun `blank x req meta header treated as absent`() {
        val bodyMeta = RequestMeta(projectId = "ifmix-demo")
        val merged = adapter.merge(request(RequestHeaders.DEV_REQ_META to "   "), bodyMeta)
        assertThat(merged).isSameInstanceAs(bodyMeta)
    }

    // ===== merge：凭证信源 =====

    @Test
    fun `authorization bearer token used when body token absent`() {
        val req = request("Authorization" to "Bearer SECRET-header")
        val merged = adapter.merge(req, RequestMeta(projectId = "ifmix-demo"))
        assertThat(merged.accessToken).isEqualTo("SECRET-header")
    }

    @Test
    fun `authorization raw token without bearer prefix accepted`() {
        val req = request("Authorization" to "SECRET-raw")
        val merged = adapter.merge(req, RequestMeta(projectId = "ifmix-demo"))
        assertThat(merged.accessToken).isEqualTo("SECRET-raw")
    }

    @Test
    fun `body access token wins over authorization header`() {
        val req = request("Authorization" to "Bearer SECRET-header")
        val merged = adapter.merge(req, RequestMeta(accessToken = "SECRET-body"))
        assertThat(merged.accessToken).isEqualTo("SECRET-body")
    }

    @Test
    fun `access token inside x req meta json is ignored credential comes from authorization`() {
        val req = request(
            RequestHeaders.DEV_REQ_META to """{"projectId":"ifmix-demo","accessToken":"SECRET-leak"}""",
            "Authorization" to "Bearer SECRET-header",
        )
        val merged = adapter.merge(req, null)
        assertThat(merged.accessToken).isEqualTo("SECRET-header")
        assertThat(merged.projectId).isEqualTo("ifmix-demo")
    }

    @Test
    fun `bearer only prefix yields null token`() {
        val req = request("Authorization" to "Bearer ")
        val merged = adapter.merge(req, RequestMeta(projectId = "ifmix-demo"))
        assertThat(merged.accessToken).isNull()
    }

    // ===== merge：fail-fast =====

    @Test
    fun `invalid x req meta json throws INVALID_REQUEST`() {
        val ex = assertThrows<ApiError> {
            adapter.merge(request(RequestHeaders.DEV_REQ_META to "{not-json"), RequestMeta())
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
        assertThat(ex.message).isEqualTo("invalid x-req-meta header: not valid RequestMeta JSON")
    }

    // ===== factory 集成：adapter 注入后 header 合并端到端生效 =====

    private val jwt = mock<AuthJwtService>()
    private val factoryWithDev = ActionContextFactory(jwt, strict = true, devHeaderAdapter = adapter)

    @AfterEach
    fun cleanup() {
        LogContext.clear()
        Mockito.clearInvocations(jwt)
    }

    @Test
    fun `factory resolves actor from authorization header and header meta`() {
        val cid = UUID.randomUUID()
        Mockito.`when`(jwt.verify(any())).thenReturn(
            VerifiedToken(actorId = cid.toString(), projectId = "ifmix-demo", actorType = 10),
        )
        val req = request(
            "Authorization" to "Bearer SECRET-header",
            RequestHeaders.DEV_REQ_META to """{"projectId":"ifmix-demo"}""",
        )
        LogContext.start(req)
        val ctx = factoryWithDev.fromRpc(req, "q_demo_todo_getById", body = com.ifmix.core.api.infra.http.ApiRequestBody<Any?>(null))
        assertThat(ctx.actorId).isEqualTo(cid)
        assertThat(ctx.projectId).isEqualTo("ifmix-demo")
        // ctx.meta 持有合并结果（含 header 来源的 token）
        assertThat(ctx.meta.accessToken).isEqualTo("SECRET-header")
    }

    @Test
    fun `factory body meta wins over header base`() {
        Mockito.`when`(jwt.verify(any())).thenReturn(
            VerifiedToken(actorId = UUID.randomUUID().toString(), projectId = "ifmix-demo", actorType = 10),
        )
        val req = request(
            "Authorization" to "Bearer SECRET-header",
            RequestHeaders.DEV_REQ_META to """{"projectId":"ifmix-header","locale":"en"}""",
        )
        LogContext.start(req)
        val ctx = factoryWithDev.fromRpc(
            req,
            "q_demo_todo_getById",
            body = com.ifmix.core.api.infra.http.ApiRequestBody<Any?>(
                RequestMeta(projectId = "ifmix-demo", accessToken = "SECRET-body"),
            ),
        )
        // aud 校验用合并后的 projectId：body 的 ifmix-demo 与 token aud 一致 → 通过
        assertThat(ctx.projectId).isEqualTo("ifmix-demo")
        // 合并结果：token 取 body、locale 回落 header 底座
        assertThat(ctx.meta.accessToken).isEqualTo("SECRET-body")
        assertThat(ctx.locale).isEqualTo("en")
    }
}
