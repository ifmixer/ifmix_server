package com.ifmix.core.api.infra.auth

import com.ifmix.core.api.entity.common.ActorTypes
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.RequestHeaders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import java.util.UUID

/**
 * RequestParser 单测：解析 + 校验（抛错）全在 parser 内。
 */
class RequestParserTest {
    private val jwt = mock<AuthJwtService>()
    private val parser = RequestParser(jwt)                       // strict=true（默认，测试环境）
    private val lenient = RequestParser(jwt, strict = false)      // strict=false（线上：WARN 不报错）

    private val projectId = "test-app"
    private val actorId = "00000000-0000-0000-0000-000000000001"

    private fun req(vararg headers: Pair<String, String>) =
        MockHttpServletRequest().apply { headers.forEach { (k, v) -> addHeader(k, v) } }

    private fun bearer(v: VerifiedToken?) = whenever(jwt.verify("tok")).thenReturn(v)

    // ── projectId ──
    @Test fun `projectId valid`() {
        assertThat(parser.parseProjectId(req(RequestHeaders.PROJECT_ID to projectId), required = true)).isEqualTo(projectId)
    }
    @Test fun `projectId required missing throws required`() {
        val ex = assertThrows<ApiError> { parser.parseProjectId(req(), required = true) }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
        assertThat(ex.message).contains("required")
    }
    @Test fun `projectId malformed throws invalid format (even not required)`() {
        val ex = assertThrows<ApiError> { parser.parseProjectId(req(RequestHeaders.PROJECT_ID to "1bad"), required = false) }
        assertThat(ex.message).contains("invalid")
    }
    @Test fun `projectId absent not required returns null`() {
        assertThat(parser.parseProjectId(req(), required = false)).isNull()
    }

    // ── clientPlatform ──
    @Test fun `clientPlatform malformed throws`() {
        assertThrows<ApiError> { parser.parseClientPlatform(req(RequestHeaders.CLIENT_PLATFORM to "BOGUS")) }
    }
    @Test fun `clientPlatform absent null`() {
        assertThat(parser.parseClientPlatform(req())).isNull()
    }

    // ── locale / currency / country：规范化 + 校验，非法抛 ──
    @Test fun `currency normalized to uppercase`() {
        assertThat(parser.parseCurrency(req(RequestHeaders.CURRENCY to "usd"))).isEqualTo("USD")
    }
    @Test fun `currency malformed throws`() {
        assertThrows<ApiError> { parser.parseCurrency(req(RequestHeaders.CURRENCY to "usdd")) }
    }
    @Test fun `country normalized to uppercase`() {
        assertThat(parser.parseCountry(req(RequestHeaders.COUNTRY to "cn"))).isEqualTo("CN")
    }
    @Test fun `country malformed throws`() {
        assertThrows<ApiError> { parser.parseCountry(req(RequestHeaders.COUNTRY to "CHN")) }
    }
    @Test fun `locale normalized to supported set`() {
        assertThat(parser.parseLocale(req(RequestHeaders.LOCALE to "zh-cn"))).isEqualTo("zh-CN")
        assertThat(parser.parseLocale(req(RequestHeaders.LOCALE to "en-US"))).isEqualTo("en")
        assertThat(parser.parseLocale(req(RequestHeaders.LOCALE to "pt-BR"))).isEqualTo("pt")
        assertThat(parser.parseLocale(req(RequestHeaders.LOCALE to "ja-JP"))).isEqualTo("ja")
    }
    @Test fun `locale chinese simplified vs traditional`() {
        // 简体
        assertThat(parser.parseLocale(req(RequestHeaders.LOCALE to "zh"))).isEqualTo("zh-CN")
        assertThat(parser.parseLocale(req(RequestHeaders.LOCALE to "zh-Hans"))).isEqualTo("zh-CN")
        assertThat(parser.parseLocale(req(RequestHeaders.LOCALE to "zh-SG"))).isEqualTo("zh-CN")
        // 繁体
        assertThat(parser.parseLocale(req(RequestHeaders.LOCALE to "zh-TW"))).isEqualTo("zh-TW")
        assertThat(parser.parseLocale(req(RequestHeaders.LOCALE to "zh-HK"))).isEqualTo("zh-TW")
        assertThat(parser.parseLocale(req(RequestHeaders.LOCALE to "zh-Hant-HK"))).isEqualTo("zh-TW")
    }
    @Test fun `locale unsupported returns null (not throw)`() {
        assertThat(parser.parseLocale(req(RequestHeaders.LOCALE to "ko"))).isNull()
        assertThat(parser.parseLocale(req(RequestHeaders.LOCALE to "ru-RU"))).isNull()
    }
    @Test fun `optional header absent returns null`() {
        assertThat(parser.parseCurrency(req())).isNull()
    }

    // ── version 格式校验（strict 抛 / lenient 返回 null）──
    @Test fun `app-version valid`() {
        assertThat(parser.parseAppVersion(req(RequestHeaders.APP_VERSION to "1.2.0"))).isEqualTo("1.2.0")
    }
    @Test fun `app-version malformed throws in strict`() {
        assertThrows<ApiError> { parser.parseAppVersion(req(RequestHeaders.APP_VERSION to "1.2")) }
        assertThrows<ApiError> { parser.parseAppVersion(req(RequestHeaders.APP_VERSION to "v1.2.3")) }
    }
    @Test fun `build-version and update-version integer`() {
        assertThat(parser.parseBuildVersion(req(RequestHeaders.BUILD_VERSION to "42"))).isEqualTo("42")
        assertThat(parser.parseUpdateVersion(req(RequestHeaders.UPDATE_VERSION to "7"))).isEqualTo("7")
        assertThrows<ApiError> { parser.parseBuildVersion(req(RequestHeaders.BUILD_VERSION to "1.0")) }
        assertThrows<ApiError> { parser.parseUpdateVersion(req(RequestHeaders.UPDATE_VERSION to "-1")) }
    }

    // ── 线上（strict=false）：格式非法不报错，返回 null（打 WARN log）──
    @Test fun `lenient mode does not throw on bad format, returns null`() {
        assertThat(lenient.parseCurrency(req(RequestHeaders.CURRENCY to "usdd"))).isNull()
        assertThat(lenient.parseCountry(req(RequestHeaders.COUNTRY to "CHN"))).isNull()
        assertThat(lenient.parseAppVersion(req(RequestHeaders.APP_VERSION to "bad"))).isNull()
        assertThat(lenient.parseBuildVersion(req(RequestHeaders.BUILD_VERSION to "x"))).isNull()
        assertThat(lenient.parseLocale(req(RequestHeaders.LOCALE to "!!bad"))).isNull()
    }
    @Test fun `lenient mode still returns valid values normally`() {
        assertThat(lenient.parseCurrency(req(RequestHeaders.CURRENCY to "usd"))).isEqualTo("USD")
        assertThat(lenient.parseLocale(req(RequestHeaders.LOCALE to "zh-cn"))).isEqualTo("zh-CN")
        assertThat(lenient.parseAppVersion(req(RequestHeaders.APP_VERSION to "2.0.1"))).isEqualTo("2.0.1")
    }
    @Test fun `strict mode throws on malformed locale`() {
        // 无法解析出 language subtag → 格式非法 → strict 抛
        assertThrows<ApiError> { parser.parseLocale(req(RequestHeaders.LOCALE to "!!bad")) }
    }
    @Test fun `unsupported-but-valid locale returns null even in strict (not a format bug)`() {
        // ko/ru 是合法 BCP 47，只是不支持 → 任何环境都 null，不抛
        assertThat(parser.parseLocale(req(RequestHeaders.LOCALE to "ko"))).isNull()
        assertThat(parser.parseLocale(req(RequestHeaders.LOCALE to "ru-RU"))).isNull()
    }

    // ── parseActor：抛错全在此 ──
    @Test fun `no token and requireActorType (login required) throws UNAUTHORIZED`() {
        val ex = assertThrows<ApiError> { parser.parseActor(req(RequestHeaders.PROJECT_ID to projectId), requireActorType = ActorTypes.CUSTOMER) }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }
    @Test fun `no token and not required returns null`() {
        assertThat(parser.parseActor(req(RequestHeaders.PROJECT_ID to projectId), requireActorType = null)).isNull()
    }
    @Test fun `valid token returns Actor`() {
        bearer(VerifiedToken(actorId = actorId, projectId = projectId, actorType = ActorTypes.CUSTOMER, anonymous = true))
        val a = parser.parseActor(req(RequestHeaders.PROJECT_ID to projectId, "Authorization" to "Bearer tok"), requireActorType = ActorTypes.CUSTOMER)!!
        assertThat(a.actorId).isEqualTo(UUID.fromString(actorId))
        assertThat(a.anonymous).isTrue()
    }
    @Test fun `expired token throws TOKEN_EXPIRED regardless of require`() {
        whenever(jwt.verify("tok")).thenThrow(TokenExpiredException())
        val ex = assertThrows<ApiError> { parser.parseActor(req("Authorization" to "Bearer tok"), requireActorType = null) }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.TOKEN_EXPIRED)
    }
    @Test fun `invalid token throws UNAUTHORIZED`() {
        bearer(null)
        val ex = assertThrows<ApiError> { parser.parseActor(req("Authorization" to "Bearer tok"), requireActorType = null) }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }
    @Test fun `token aud mismatch (cross-app) throws UNAUTHORIZED`() {
        // token.aud != header x-project-id → INVALID → 401000（防跨 app 重放）
        bearer(VerifiedToken(actorId = actorId, projectId = "other-app", actorType = ActorTypes.CUSTOMER))
        val ex = assertThrows<ApiError> {
            parser.parseActor(req(RequestHeaders.PROJECT_ID to projectId, "Authorization" to "Bearer tok"), requireActorType = ActorTypes.CUSTOMER)
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }
    @Test fun `wrong actorType throws FORBIDDEN`() {
        bearer(VerifiedToken(actorId = actorId, projectId = projectId, actorType = ActorTypes.MANAGER))
        val ex = assertThrows<ApiError> { parser.parseActor(req(RequestHeaders.PROJECT_ID to projectId, "Authorization" to "Bearer tok"), requireActorType = ActorTypes.CUSTOMER) }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.FORBIDDEN)
    }

    // ── peekActorId：不抛 ──
    @Test fun `peekActorId returns actorId for valid, null otherwise`() {
        bearer(VerifiedToken(actorId = actorId, projectId = projectId, actorType = ActorTypes.CUSTOMER))
        assertThat(parser.peekActorId(req(RequestHeaders.PROJECT_ID to projectId, "Authorization" to "Bearer tok")))
            .isEqualTo(UUID.fromString(actorId))
        assertThat(parser.peekActorId(req())).isNull()
    }
}
