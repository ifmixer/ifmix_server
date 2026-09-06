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

    private val appId = "00000000-0000-0000-0000-000000000099"
    private val actorId = "00000000-0000-0000-0000-000000000001"

    private fun req(vararg headers: Pair<String, String>) =
        MockHttpServletRequest().apply { headers.forEach { (k, v) -> addHeader(k, v) } }

    private fun bearer(v: VerifiedToken?) = whenever(jwt.verify("tok")).thenReturn(v)

    // ── appId ──
    @Test fun `appId valid`() {
        assertThat(parser.parseAppId(req(RequestHeaders.APP_ID to appId), required = true)).isEqualTo(UUID.fromString(appId))
    }
    @Test fun `appId required missing throws required`() {
        val ex = assertThrows<ApiError> { parser.parseAppId(req(), required = true) }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
        assertThat(ex.message).contains("required")
    }
    @Test fun `appId malformed throws invalid format (even not required)`() {
        val ex = assertThrows<ApiError> { parser.parseAppId(req(RequestHeaders.APP_ID to "bad"), required = false) }
        assertThat(ex.message).contains("invalid")
    }
    @Test fun `appId absent not required returns null`() {
        assertThat(parser.parseAppId(req(), required = false)).isNull()
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
    @Test fun `locale normalized to BCP47`() {
        assertThat(parser.parseLocale(req(RequestHeaders.LOCALE to "zh-cn"))).isEqualTo("zh-CN")
    }
    @Test fun `locale malformed throws`() {
        assertThrows<ApiError> { parser.parseLocale(req(RequestHeaders.LOCALE to "!!bad")) }
    }
    @Test fun `optional header absent returns null`() {
        assertThat(parser.parseCurrency(req())).isNull()
    }

    // ── parseActor：抛错全在此 ──
    @Test fun `no token and requireActorType (login required) throws UNAUTHORIZED`() {
        val ex = assertThrows<ApiError> { parser.parseActor(req(RequestHeaders.APP_ID to appId), requireActorType = ActorTypes.CUSTOMER) }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }
    @Test fun `no token and not required returns null`() {
        assertThat(parser.parseActor(req(RequestHeaders.APP_ID to appId), requireActorType = null)).isNull()
    }
    @Test fun `valid token returns Actor`() {
        bearer(VerifiedToken(actorId = actorId, appId = appId, actorType = ActorTypes.CUSTOMER, anonymous = true))
        val a = parser.parseActor(req(RequestHeaders.APP_ID to appId, "Authorization" to "Bearer tok"), requireActorType = ActorTypes.CUSTOMER)!!
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
        // token.aud != header x-app-id → INVALID → 401000（防跨 app 重放）
        bearer(VerifiedToken(actorId = actorId, appId = "00000000-0000-0000-0000-000000000077", actorType = ActorTypes.CUSTOMER))
        val ex = assertThrows<ApiError> {
            parser.parseActor(req(RequestHeaders.APP_ID to appId, "Authorization" to "Bearer tok"), requireActorType = ActorTypes.CUSTOMER)
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }
    @Test fun `wrong actorType throws FORBIDDEN`() {
        bearer(VerifiedToken(actorId = actorId, appId = appId, actorType = ActorTypes.MANAGER))
        val ex = assertThrows<ApiError> { parser.parseActor(req(RequestHeaders.APP_ID to appId, "Authorization" to "Bearer tok"), requireActorType = ActorTypes.CUSTOMER) }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.FORBIDDEN)
    }

    // ── peekActorId：不抛 ──
    @Test fun `peekActorId returns actorId for valid, null otherwise`() {
        bearer(VerifiedToken(actorId = actorId, appId = appId, actorType = ActorTypes.CUSTOMER))
        assertThat(parser.peekActorId(req(RequestHeaders.APP_ID to appId, "Authorization" to "Bearer tok")))
            .isEqualTo(UUID.fromString(actorId))
        assertThat(parser.peekActorId(req())).isNull()
    }
}
