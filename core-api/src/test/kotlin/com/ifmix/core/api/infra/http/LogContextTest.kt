package com.ifmix.core.api.infra.http

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.util.UUID

class LogContextTest {

    @Test
    fun `bind writes ordered fields with dash for null, clear removes`() {
        val iid = UUID.randomUUID()
        LogContext.bind(ActionContext(tokenInstallId = iid, clientIp = "1.2.3.4", botScore = 12))
        assertThat(MDC.get("iid")).isEqualTo(iid.toString())
        assertThat(MDC.get("ip")).isEqualTo("1.2.3.4")
        assertThat(MDC.get("bot")).isEqualTo("12")
        assertThat(MDC.get("cid")).isNull() // null 不写
        LogContext.clear()
        assertThat(MDC.getCopyOfContextMap().orEmpty().keys.intersect(ActionContext().logFields().keys)).isEqualTo(emptySet())
    }

    @Test
    fun `ctx bound on fetcher thread is recoverable on filter thread via request`() {
        val request = org.springframework.mock.web.MockHttpServletRequest()
        val cid = UUID.randomUUID()
        Thread.ofVirtual().start { LogContext.bind(ActionContext(actorId = cid, clientIp = "5.6.7.8"), request) }.join()
        assertThat(MDC.get("cid")).isNull() // 当前线程没绑
        assertThat(LogContext.bindFrom(request)).isEqualTo(true)
        assertThat(MDC.get("cid")).isEqualTo(cid.toString())
        assertThat(MDC.get("ip")).isEqualTo("5.6.7.8")
        LogContext.clear()
    }

    @Test
    fun `start generates request id, binds rid early, and stores it on request`() {
        val request = org.springframework.mock.web.MockHttpServletRequest()
        val rid = LogContext.start(request)
        assertThat(MDC.get("rid")).isEqualTo(rid)
        assertThat(LogContext.requestId(request)).isEqualTo(rid)
        LogContext.clear()
    }

    @Test
    fun `client x-req-id used as-is (control chars stripped), generated when absent or blank`() {
        fun startWith(v: String?) = LogContext.start(org.springframework.mock.web.MockHttpServletRequest().apply { v?.let { addHeader("x-req-id", it) } })
        assertThat(startWith("app-AbC_123")).isEqualTo("app-AbC_123")
        assertThat(startWith("a b\nINFO fake")).isEqualTo("a bINFO fake")
        assertThat(startWith("x".repeat(500)).length).isEqualTo(128)
        for (v in listOf(null, "", "   ", "\n")) {
            val rid = startWith(v)
            assertThat(UUID.fromString(rid).toString()).isEqualTo(rid)
        }
        LogContext.clear()
    }
}
