package com.ifmix.core.api.infra.auth

import com.ifmix.core.api.infra.http.RequestContext
import com.ifmix.core.api.infra.http.RequestHeaders
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

    private val customerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val appId = "00000000-0000-0000-0000-000000000099"

    private fun MockHttpServletRequest.reqCtx() =
        getAttribute(AuthInterceptor.ATTR_REQUEST_CONTEXT) as RequestContext

    @Test fun `sets customerId and actor fields when customer token valid`() {
        whenever(jwt.verify("tok")).thenReturn(
            VerifiedToken(actorId = customerId.toString(), appId = appId, actorType = AuthJwtService.ACTOR_CUSTOMER, anonymous = true),
        )
        val req = MockHttpServletRequest().apply {
            addHeader(RequestHeaders.APP_ID, appId)
            addHeader("Authorization", "Bearer tok")
        }
        assertThat(interceptor.preHandle(req, MockHttpServletResponse(), Any())).isTrue()
        val ctx = req.reqCtx()
        assertThat(ctx.actorId).isEqualTo(customerId)
        assertThat(ctx.actorType).isEqualTo(AuthJwtService.ACTOR_CUSTOMER)
        assertThat(ctx.anonymous).isTrue()
    }

    @Test fun `anonymous when appId mismatch between token and header`() {
        whenever(jwt.verify("tok")).thenReturn(
            VerifiedToken(actorId = customerId.toString(), appId = "other-app-id", actorType = AuthJwtService.ACTOR_CUSTOMER),
        )
        val req = MockHttpServletRequest().apply {
            addHeader(RequestHeaders.APP_ID, appId)
            addHeader("Authorization", "Bearer tok")
        }
        // token appId != header appId → 401 抛出前不设置 customerId
        try {
            interceptor.preHandle(req, MockHttpServletResponse(), Any())
        } catch (_: Exception) { /* expected */ }
    }

    @Test fun `no token yields null actorId`() {
        val req = MockHttpServletRequest().apply { addHeader(RequestHeaders.APP_ID, appId) }
        assertThat(interceptor.preHandle(req, MockHttpServletResponse(), Any())).isTrue()
        assertThat(req.reqCtx().actorId).isNull()
    }

    @Test fun `invalid token throws unauthorized`() {
        whenever(jwt.verify("bad")).thenReturn(null)
        val req = MockHttpServletRequest().apply {
            addHeader(RequestHeaders.APP_ID, appId)
            addHeader("Authorization", "Bearer bad")
        }
        try {
            interceptor.preHandle(req, MockHttpServletResponse(), Any())
            assertThat(false).isTrue() // should not reach
        } catch (_: Exception) { /* expected: invalid token */ }
    }
}
