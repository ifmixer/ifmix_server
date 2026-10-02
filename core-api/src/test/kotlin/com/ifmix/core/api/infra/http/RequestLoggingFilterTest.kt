package com.ifmix.core.api.infra.http

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.ifmix.core.api.infra.auth.RequestParser
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper

/**
 * filter 嵌套顺序：RequestLoggingFilter（外）→ GraphQlHttpStatusFilter（内）→ handler。
 * 内层按 errors[0].extensions.code 改写的 status 要传到外层（日志读到的 httpStatus 即客户端收到的），body 不丢。
 */
class RequestLoggingFilterTest {

    private val logging = RequestLoggingFilter(mock<RequestParser>())
    private val status = GraphQlHttpStatusFilter(JsonMapper.builder().build())
    private val body = """{"errors":[{"message":"x","extensions":{"code":"503000"}}]}"""

    private fun run(path: String): MockHttpServletResponse {
        val req = MockHttpServletRequest("POST", path)
        val res = MockHttpServletResponse()
        val handler = object : jakarta.servlet.http.HttpServlet() {
            override fun service(rq: jakarta.servlet.ServletRequest, rs: jakarta.servlet.ServletResponse) {
                rs.writer.write(body)
            }
        }
        logging.doFilter(req, res, MockFilterChain(handler, status))
        return res
    }

    @Test
    fun `inner status rewrite reaches outer logging filter and client, body intact`() {
        val res = run("/customer/core/greq/m_x")
        assertThat(res.status).isEqualTo(503)
        assertThat(res.contentAsString).isEqualTo(body)
        assertThat(res.getHeader(RequestHeaders.REQ_ID)).isNotNull()
    }

    @Test
    fun `root and health are not logged (filter skipped)`() {
        assertThat(run("/").getHeader(RequestHeaders.REQ_ID)).isNull()
        assertThat(run("/core/health").getHeader(RequestHeaders.REQ_ID)).isNull()
    }
}
