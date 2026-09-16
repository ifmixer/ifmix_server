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
    private val parser = RequestParser(jwt)

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
        assertThat(parser.parseLocale(req(RequestHeaders.LOCALE to "!!bad"))).isNull()
    }
    @Test fun `optional header absent returns null`() {
        assertThat(parser.parseCurrency(req())).isNull()
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
