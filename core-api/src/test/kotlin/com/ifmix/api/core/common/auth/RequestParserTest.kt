package com.ifmix.core.api.infra.auth

import com.ifmix.core.api.entity.common.ActorTypes
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.RequestMeta
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import java.util.UUID

/**
 * RequestParser 单测：解析 + 校验（抛错）全在 parser 内。
 * 输入是 [RequestMeta]（由 WireCryptoFilter 挂到 request attribute），不再是 x-* 请求头。
 */
class RequestParserTest {
    private val jwt = mock<AuthJwtService>()
    private val parser = RequestParser(jwt)                       // strict=true（默认，测试环境）
    private val lenient = RequestParser(jwt, strict = false)      // strict=false（线上：WARN 不报错）

    private val projectId = "test-app"
    private val actorId = "00000000-0000-0000-0000-000000000001"

    /** 构造带 meta 的 request（模拟 WireCryptoFilter 已挂 attribute）。 */
    private fun req(meta: RequestMeta? = null, vararg headers: Pair<String, String>) =
        MockHttpServletRequest().apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
            meta?.let { setAttribute(RequestMeta.ATTR_META, it) }
        }

    private fun bearer(v: VerifiedToken?) = whenever(jwt.verify("tok")).thenReturn(v)

    // ── projectId ──
    @Test fun `projectId valid`() {
        assertThat(parser.parseProjectId(req(RequestMeta(projectId = projectId)), required = true)).isEqualTo(projectId)
    }
    @Test fun `projectId required missing throws required`() {
        val ex = assertThrows<ApiError> { parser.parseProjectId(req(), required = true) }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
        assertThat(ex.message).contains("required")
    }
    @Test fun `projectId malformed throws invalid format (even not required)`() {
        val ex = assertThrows<ApiError> { parser.parseProjectId(req(RequestMeta(projectId = "1bad")), required = false) }
        assertThat(ex.message).contains("invalid")
    }
    @Test fun `projectId absent not required returns null`() {
        assertThat(parser.parseProjectId(req(), required = false)).isNull()
    }
    @Test fun `projectId result cached on request`() {
        val r = req(RequestMeta(projectId = projectId))
        assertThat(parser.parseProjectId(r, required = true)).isEqualTo(projectId)
        // 第二次直接读缓存
        r.setAttribute(RequestMeta.ATTR_META, RequestMeta(projectId = "tampered"))
        assertThat(parser.parseProjectId(r, required = true)).isEqualTo(projectId)
    }

    // ── clientPlatform ──
    @Test fun `clientPlatform malformed throws`() {
        assertThrows<ApiError> { parser.parseClientPlatform(req(RequestMeta(clientPlatform = "BOGUS"))) }
    }
    @Test fun `clientPlatform valid normalized`() {
        assertThat(parser.parseClientPlatform(req(RequestMeta(clientPlatform = "android")))).isNotNull
    }
    @Test fun `clientPlatform absent null`() {
        assertThat(parser.parseClientPlatform(req())).isNull()
    }

    // ── locale / currency / country：规范化 + 校验，非法抛 ──
    @Test fun `currency normalized to uppercase`() {
        assertThat(parser.parseCurrency(req(RequestMeta(currency = "usd")))).isEqualTo("USD")
    }
    @Test fun `currency malformed throws`() {
        assertThrows<ApiError> { parser.parseCurrency(req(RequestMeta(currency = "usdd"))) }
    }
    @Test fun `country normalized to uppercase`() {
        assertThat(parser.parseCountry(req(RequestMeta(country = "cn")))).isEqualTo("CN")
    }
    @Test fun `country malformed throws`() {
        assertThrows<ApiError> { parser.parseCountry(req(RequestMeta(country = "CHN"))) }
    }
    @Test fun `locale normalized to supported set`() {
        assertThat(parser.parseLocale(req(RequestMeta(locale = "zh-cn")))).isEqualTo("zh-CN")
        assertThat(parser.parseLocale(req(RequestMeta(locale = "en-US")))).isEqualTo("en")
        assertThat(parser.parseLocale(req(RequestMeta(locale = "pt-BR")))).isEqualTo("pt")
        assertThat(parser.parseLocale(req(RequestMeta(locale = "ja-JP")))).isEqualTo("ja")
    }
    @Test fun `locale chinese simplified vs traditional`() {
        // 简体
        assertThat(parser.parseLocale(req(RequestMeta(locale = "zh")))).isEqualTo("zh-CN")
        assertThat(parser.parseLocale(req(RequestMeta(locale = "zh-Hans")))).isEqualTo("zh-CN")
        assertThat(parser.parseLocale(req(RequestMeta(locale = "zh-SG")))).isEqualTo("zh-CN")
        // 繁体
        assertThat(parser.parseLocale(req(RequestMeta(locale = "zh-TW")))).isEqualTo("zh-TW")
        assertThat(parser.parseLocale(req(RequestMeta(locale = "zh-HK")))).isEqualTo("zh-TW")
        assertThat(parser.parseLocale(req(RequestMeta(locale = "zh-Hant-HK")))).isEqualTo("zh-TW")
    }
    @Test fun `locale unsupported returns null (not throw)`() {
        assertThat(parser.parseLocale(req(RequestMeta(locale = "ko")))).isNull()
        assertThat(parser.parseLocale(req(RequestMeta(locale = "ru-RU")))).isNull()
    }
    @Test fun `optional field absent returns null`() {
        assertThat(parser.parseCurrency(req())).isNull()
    }

    // ── version 原样透传（不校验格式，任何环境都不抛）──
    @Test fun `version fields pass through as-is`() {
        assertThat(parser.parseAppVersion(req(RequestMeta(appVersion = "0.1.1")))).isEqualTo("0.1.1")
        // otaVersion 形如 runtimeVersion-buildNumber-otaSeq，照样透传
        assertThat(parser.parseOtaVersion(req(RequestMeta(otaVersion = "1-23-3")))).isEqualTo("1-23-3")
        // 任意格式都不校验
        assertThat(parser.parseAppVersion(req(RequestMeta(appVersion = "v1.2-beta")))).isEqualTo("v1.2-beta")
        assertThat(parser.parseAppVersion(req())).isNull()
    }

    // ── 线上（strict=false）：格式非法不报错，返回 null（打 WARN log）──
    @Test fun `lenient mode does not throw on bad format, returns null`() {
        assertThat(lenient.parseCurrency(req(RequestMeta(currency = "usdd")))).isNull()
        assertThat(lenient.parseCountry(req(RequestMeta(country = "CHN")))).isNull()
        assertThat(lenient.parseLocale(req(RequestMeta(locale = "!!bad")))).isNull()
    }
    @Test fun `lenient mode still returns valid values normally`() {
        assertThat(lenient.parseCurrency(req(RequestMeta(currency = "usd")))).isEqualTo("USD")
        assertThat(lenient.parseLocale(req(RequestMeta(locale = "zh-cn")))).isEqualTo("zh-CN")
    }
    @Test fun `strict mode throws on malformed locale`() {
        // 无法解析出 language subtag → 格式非法 → strict 抛
        assertThrows<ApiError> { parser.parseLocale(req(RequestMeta(locale = "!!bad"))) }
    }
    @Test fun `unsupported-but-valid locale returns null even in strict (not a format bug)`() {
        // ko/ru 是合法 BCP 47，只是不支持 → 任何环境都 null，不抛
        assertThat(parser.parseLocale(req(RequestMeta(locale = "ko")))).isNull()
        assertThat(parser.parseLocale(req(RequestMeta(locale = "ru-RU")))).isNull()
    }

    // ── parseActor：抛错全在此（token 来自 meta.accessToken）──
    @Test fun `no token and requireActorType (login required) throws UNAUTHORIZED`() {
        val ex = assertThrows<ApiError> { parser.parseActor(req(RequestMeta(projectId = projectId)), requireActorType = ActorTypes.CUSTOMER) }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }
    @Test fun `no token and not required returns null`() {
        assertThat(parser.parseActor(req(RequestMeta(projectId = projectId)), requireActorType = null)).isNull()
    }
    @Test fun `valid token returns Actor`() {
        bearer(VerifiedToken(actorId = actorId, projectId = projectId, actorType = ActorTypes.CUSTOMER, anonymous = true))
        val a = parser.parseActor(req(RequestMeta(projectId = projectId, accessToken = "tok"), "Authorization" to "ignored"), requireActorType = ActorTypes.CUSTOMER)!!
        assertThat(a.actorId).isEqualTo(UUID.fromString(actorId))
        assertThat(a.anonymous).isTrue()
    }
    @Test fun `accessToken with Bearer prefix is accepted`() {
        bearer(VerifiedToken(actorId = actorId, projectId = projectId, actorType = ActorTypes.CUSTOMER, anonymous = true))
        val a = parser.parseActor(req(RequestMeta(projectId = projectId, accessToken = "Bearer tok")), requireActorType = ActorTypes.CUSTOMER)!!
        assertThat(a.actorId).isEqualTo(UUID.fromString(actorId))
    }
    @Test fun `lowercase bearer prefix is accepted`() {
        bearer(VerifiedToken(actorId = actorId, projectId = projectId, actorType = ActorTypes.CUSTOMER, anonymous = true))
        assertThat(parser.parseActor(req(RequestMeta(projectId = projectId, accessToken = "bearer tok")), requireActorType = ActorTypes.CUSTOMER)).isNotNull
        assertThat(parser.parseActor(req(RequestMeta(projectId = projectId), "Authorization" to "bearer tok"), requireActorType = ActorTypes.CUSTOMER)).isNotNull
    }
    @Test fun `bare Bearer scheme without token is treated as absent`() {
        // 纯 "Bearer" 无凭证 → 按未提供处理（需登录时 UNAUTHORIZED，非 invalid token）
        val ex = assertThrows<ApiError> { parser.parseActor(req(RequestMeta(projectId = projectId), "Authorization" to "Bearer"), requireActorType = ActorTypes.CUSTOMER) }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
        assertThat(ex.message).contains("authentication required")
    }
    @Test fun `token falls back to Authorization header when meta accessToken is absent (plain dev channel)`() {
        bearer(VerifiedToken(actorId = actorId, projectId = projectId, actorType = ActorTypes.CUSTOMER, anonymous = true))
        val a = parser.parseActor(req(RequestMeta(projectId = projectId), "Authorization" to "Bearer tok"), requireActorType = ActorTypes.CUSTOMER)!!
        assertThat(a.actorId).isEqualTo(UUID.fromString(actorId))
    }
    @Test fun `meta accessToken wins over Authorization header`() {
        bearer(VerifiedToken(actorId = actorId, projectId = projectId, actorType = ActorTypes.CUSTOMER, anonymous = true))
        // meta 带 valid token（"tok"），header 带过期 token（"header-tok"）→ 只验 meta 的，正常返回
        whenever(jwt.verify("header-tok")).thenThrow(TokenExpiredException())
        val a = parser.parseActor(
            req(RequestMeta(projectId = projectId, accessToken = "tok"), "Authorization" to "Bearer header-tok"),
            requireActorType = ActorTypes.CUSTOMER,
        )!!
        assertThat(a.actorId).isEqualTo(UUID.fromString(actorId))

        // 反向：meta 无 token 时回落 header（过期 → TOKEN_EXPIRED）
        val ex = assertThrows<ApiError> {
            parser.parseActor(req(null, "Authorization" to "Bearer header-tok"), requireActorType = ActorTypes.CUSTOMER)
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.TOKEN_EXPIRED)
    }
    @Test fun `expired token throws TOKEN_EXPIRED regardless of require`() {
        whenever(jwt.verify("tok")).thenThrow(TokenExpiredException())
        val ex = assertThrows<ApiError> { parser.parseActor(req(RequestMeta(accessToken = "tok")), requireActorType = null) }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.TOKEN_EXPIRED)
    }
    @Test fun `invalid token throws UNAUTHORIZED`() {
        bearer(null)
        val ex = assertThrows<ApiError> { parser.parseActor(req(RequestMeta(accessToken = "tok")), requireActorType = null) }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }

    @Test fun `install token without subject is allowed when actor is optional`() {
        bearer(VerifiedToken(
            actorId = null,
            projectId = projectId,
            actorType = ActorTypes.CUSTOMER,
            tokenType = AuthJwtService.TOKEN_TYPE_INSTALL,
            installId = "00000000-0000-0000-0000-000000000002",
        ))

        assertThat(parser.parseActor(
            req(RequestMeta(projectId = projectId, accessToken = "tok")),
            requireActorType = null,
        )).isNull()
    }

    @Test fun `install token without subject is rejected when customer actor is required`() {
        bearer(VerifiedToken(
            actorId = null,
            projectId = projectId,
            actorType = ActorTypes.CUSTOMER,
            tokenType = AuthJwtService.TOKEN_TYPE_INSTALL,
            installId = "00000000-0000-0000-0000-000000000002",
        ))

        val ex = assertThrows<ApiError> {
            parser.parseActor(
                req(RequestMeta(projectId = projectId, accessToken = "tok")),
                requireActorType = ActorTypes.CUSTOMER,
            )
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }

    @Test fun `token aud mismatch (cross-app) throws UNAUTHORIZED`() {
        // token.aud != meta.projectId → UNAUTHORIZED（防跨 app 重放）
        bearer(VerifiedToken(actorId = actorId, projectId = "other-app", actorType = ActorTypes.CUSTOMER))
        val ex = assertThrows<ApiError> {
            parser.parseActor(req(RequestMeta(projectId = projectId, accessToken = "tok")), requireActorType = ActorTypes.CUSTOMER)
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }
    @Test fun `wrong actorType throws FORBIDDEN`() {
        bearer(VerifiedToken(actorId = actorId, projectId = projectId, actorType = ActorTypes.MANAGER))
        val ex = assertThrows<ApiError> { parser.parseActor(req(RequestMeta(projectId = projectId, accessToken = "tok")), requireActorType = ActorTypes.CUSTOMER) }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.FORBIDDEN)
    }

    // ── peekActorId：不抛 ──
    @Test fun `peekActorId returns actorId for valid, null otherwise`() {
        bearer(VerifiedToken(actorId = actorId, projectId = projectId, actorType = ActorTypes.CUSTOMER))
        assertThat(parser.peekActorId(req(RequestMeta(projectId = projectId, accessToken = "tok"))))
            .isEqualTo(UUID.fromString(actorId))
        assertThat(parser.peekActorId(req())).isNull()
    }
}
