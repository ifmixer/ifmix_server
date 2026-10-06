package com.ifmix.core.api.infra.http

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * RequestLoggingFilter 行为：写 REQ_ID 响应头、body 透传；root / health 路径跳过日志。
 * （旧链中的 GraphQlHttpStatusFilter 已随 GraphQL 引擎删除——错误 status 由
 * GlobalExceptionHandler 直接输出，无需内层改写。）
 */
class RequestLoggingFilterTest {

    private val logging = RequestLoggingFilter()
    private val body = """{"errors":[{"message":"x","extensions":{"code":"503000"}}]}"""

    private fun run(path: String): MockHttpServletResponse {
        val req = MockHttpServletRequest("POST", path)
        val res = MockHttpServletResponse()
        val handler = object : jakarta.servlet.http.HttpServlet() {
            override fun service(rq: jakarta.servlet.ServletRequest, rs: jakarta.servlet.ServletResponse) {
                rs.writer.write(body)
            }
        }
        logging.doFilter(req, res, MockFilterChain(handler))
        return res
    }

    @Test
    fun `body passes through and req id header reaches client`() {
        val res = run("/api/customer/core/m_demo_todo_getById")
        assertThat(res.status).isEqualTo(200)
        assertThat(res.contentAsString).isEqualTo(body)
        assertThat(res.getHeader(RequestHeaders.REQ_ID)).isNotNull()
    }

    @Test
    fun `root and health are not logged (filter skipped)`() {
        assertThat(run("/").getHeader(RequestHeaders.REQ_ID)).isNull()
        assertThat(run("/core/health").getHeader(RequestHeaders.REQ_ID)).isNull()
    }
}
