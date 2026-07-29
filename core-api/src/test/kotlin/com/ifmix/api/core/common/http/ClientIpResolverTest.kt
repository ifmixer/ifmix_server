package com.ifmix.api.core.infra.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class ClientIpResolverTest {

    @Test
    fun `returns first IP from X-Forwarded-For`() {
        val req = MockHttpServletRequest()
        req.addHeader("X-Forwarded-For", "203.0.113.50, 70.41.3.18, 150.172.238.178")
        assertEquals("203.0.113.50", ClientIpResolver.resolve(req))
    }

    @Test
    fun `falls back to X-Real-IP when no X-Forwarded-For`() {
        val req = MockHttpServletRequest()
        req.addHeader("X-Real-IP", "10.0.0.1")
        assertEquals("10.0.0.1", ClientIpResolver.resolve(req))
    }

    @Test
    fun `falls back to remoteAddr when no headers`() {
        val req = MockHttpServletRequest()
        req.remoteAddr = "192.168.1.1"
        assertEquals("192.168.1.1", ClientIpResolver.resolve(req))
    }

    @Test
    fun `trims whitespace from X-Forwarded-For`() {
        val req = MockHttpServletRequest()
        req.addHeader("X-Forwarded-For", "  10.0.0.5  ")
        assertEquals("10.0.0.5", ClientIpResolver.resolve(req))
    }

    @Test
    fun `prefers X-Forwarded-For over X-Real-IP`() {
        val req = MockHttpServletRequest()
        req.addHeader("X-Forwarded-For", "1.2.3.4")
        req.addHeader("X-Real-IP", "5.6.7.8")
        assertEquals("1.2.3.4", ClientIpResolver.resolve(req))
    }
}
