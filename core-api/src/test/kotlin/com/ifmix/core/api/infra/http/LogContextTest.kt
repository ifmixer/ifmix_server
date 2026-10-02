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
        assertThat(MDC.get(LogContext.KEY)).isEqualTo("rid=- pid=- iid=$iid cid=- ip=1.2.3.4 bot=12 plat=- av=- ov=- loc=- cur=- cty=-")
        LogContext.clear()
        assertThat(MDC.get(LogContext.KEY)).isNull()
    }

    @Test
    fun `ctx bound on fetcher thread is recoverable on filter thread via request`() {
        val request = org.springframework.mock.web.MockHttpServletRequest()
        val cid = UUID.randomUUID()
        Thread.ofVirtual().start { LogContext.bind(ActionContext(actorId = cid, clientIp = "5.6.7.8"), request) }.join()
        assertThat(MDC.get(LogContext.KEY)).isNull() // 当前线程没绑
        assertThat(LogContext.bindFrom(request)).isEqualTo(true)
        assertThat(MDC.get(LogContext.KEY)).isEqualTo("rid=- pid=- iid=- cid=$cid ip=5.6.7.8 bot=- plat=- av=- ov=- loc=- cur=- cty=-")
        LogContext.clear()
    }

    @Test
    fun `start generates request id, binds rid early, and stores it on request`() {
        val request = org.springframework.mock.web.MockHttpServletRequest()
        val rid = LogContext.start(request)
        assertThat(MDC.get(LogContext.KEY)).isEqualTo("rid=$rid")
        assertThat(LogContext.requestId(request)).isEqualTo(rid)
        LogContext.clear()
    }
}
