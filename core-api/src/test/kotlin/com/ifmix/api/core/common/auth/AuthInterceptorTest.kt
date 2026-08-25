package com.ifmix.api.core.infra.auth

import com.ifmix.api.core.infra.http.RequestHeaders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID

class AuthInterceptorTest {
    private val jwt = mock<AuthJwtService>()
    private val interceptor = AuthInterceptor(jwt)

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val installId = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val appId = "00000000-0000-0000-0000-000000000099"

    @Test fun `sets userId and installId attributes when user token valid`() {
        whenever(jwt.verify("tok")).thenReturn(VerifiedToken(userId = userId.toString(), installId = installId.toString(), appId = appId, type = AuthJwtService.TYPE_USER))
        val req = MockHttpServletRequest().apply {
            addHeader(RequestHeaders.APP_ID, appId)
            addHeader("Authorization", "Bearer tok")
        }
        assertThat(interceptor.preHandle(req, MockHttpServletResponse(), Any())).isTrue()
        assertThat(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).isEqualTo(userId)
        assertThat(req.getAttribute(AuthInterceptor.ATTR_INSTALL_ID)).isEqualTo(installId)
    }

    @Test fun `sets only installId when install token valid`() {
        whenever(jwt.verify("itok")).thenReturn(VerifiedToken(userId = null, installId = installId.toString(), appId = appId, type = AuthJwtService.TYPE_INSTALL))
        val req = MockHttpServletRequest().apply {
            addHeader(RequestHeaders.APP_ID, appId)
            addHeader("Authorization", "Bearer itok")
        }
        assertThat(interceptor.preHandle(req, MockHttpServletResponse(), Any())).isTrue()
        assertThat(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).isNull()
        assertThat(req.getAttribute(AuthInterceptor.ATTR_INSTALL_ID)).isEqualTo(installId)
    }

    @Test fun `anonymous when appId mismatch between token and header`() {
        whenever(jwt.verify("tok")).thenReturn(VerifiedToken(userId = userId.toString(), installId = installId.toString(), appId = "other-app-id", type = AuthJwtService.TYPE_USER))
        val req = MockHttpServletRequest().apply {
            addHeader(RequestHeaders.APP_ID, appId)
            addHeader("Authorization", "Bearer tok")
        }
        interceptor.preHandle(req, MockHttpServletResponse(), Any())
        assertThat(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).isNull()
    }

    @Test fun `anonymous when no authorization header`() {
        val req = MockHttpServletRequest().apply { addHeader(RequestHeaders.APP_ID, appId) }
        assertThat(interceptor.preHandle(req, MockHttpServletResponse(), Any())).isTrue()
        assertThat(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).isNull()
    }

    @Test fun `anonymous when token invalid`() {
        whenever(jwt.verify("bad")).thenReturn(null)
        val req = MockHttpServletRequest().apply {
            addHeader(RequestHeaders.APP_ID, appId)
            addHeader("Authorization", "Bearer bad")
        }
        interceptor.preHandle(req, MockHttpServletResponse(), Any())
        assertThat(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).isNull()
        assertThat(req.getAttribute(AuthInterceptor.ATTR_INSTALL_ID)).isNull()
    }
}
