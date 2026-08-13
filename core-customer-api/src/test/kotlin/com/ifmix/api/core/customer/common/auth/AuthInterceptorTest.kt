package com.ifmix.api.core.common.infra.auth

import com.ifmix.api.core.common.infra.http.RequestHeaders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class AuthInterceptorTest {
    private val jwt = mock<AuthJwtService>()
    private val interceptor = AuthInterceptor(jwt)

    @Test fun `sets userId attribute when token valid and aid matches`() {
        whenever(jwt.verifyAccess("tok", "app1")).thenReturn("user1")
        val req = MockHttpServletRequest().apply {
            addHeader(RequestHeaders.APP_ID, "app1")
            addHeader("Authorization", "Bearer tok")
        }
        assertThat(interceptor.preHandle(req, MockHttpServletResponse(), Any())).isTrue()
        assertThat(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).isEqualTo("user1")
    }

    @Test fun `anonymous when no authorization header`() {
        val req = MockHttpServletRequest().apply { addHeader(RequestHeaders.APP_ID, "app1") }
        assertThat(interceptor.preHandle(req, MockHttpServletResponse(), Any())).isTrue()
        assertThat(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).isNull()
    }

    @Test fun `anonymous when token invalid`() {
        whenever(jwt.verifyAccess("bad", "app1")).thenReturn(null)
        val req = MockHttpServletRequest().apply {
            addHeader(RequestHeaders.APP_ID, "app1")
            addHeader("Authorization", "Bearer bad")
        }
        interceptor.preHandle(req, MockHttpServletResponse(), Any())
        assertThat(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).isNull()
    }
}
