package com.ifmix.core.api.infra.http

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.ifmix.core.api.infra.auth.RequestParser
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper

/**
 * filter 嵌套顺序：RequestLoggingFilter（外）→ GraphQlHttpStatusFilter（内）→ handler。
 * 内层按 errors[0].extensions.code 改写的 status 要传到外层（日志读到的 httpStatus 即客户端收到的），body 不丢。
 */
class RequestLoggingFilterTest {

    private val parser = mock<RequestParser>().also {
        whenever(it.parseMeta(any())).thenReturn(RequestMeta())
    }
    private val logging = RequestLoggingFilter(parser)
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
        // filter 出口清理 ThreadLocal（虚拟线程池防泄漏）：本测试线程 MDC 里 rid 已被 clear
        assertThat(org.slf4j.MDC.get("rid")).isNull()
    }

    @Test
    fun `root and health are not logged (filter skipped)`() {
        // 跳过路径：filter 直接透传，不建立 rid（LogContext.start 未被调用）
        for (path in listOf("/", "/core/health")) {
            val req = MockHttpServletRequest("POST", path)
            val res = MockHttpServletResponse()
            val handler = object : jakarta.servlet.http.HttpServlet() {
                override fun service(rq: jakarta.servlet.ServletRequest, rs: jakarta.servlet.ServletResponse) {
                    rs.writer.write("ok")
                }
            }
            logging.doFilter(req, res, MockFilterChain(handler))
            assertThat(LogContext.requestId(req)).isNull()
            assertThat(res.contentAsString).isEqualTo("ok")
        }
    }

    @Test
    fun `parseMeta throwing does not break the filter chain (error surfaces at business entry)`() {
        // 非法 x-req-meta 等解析失败不能在 filter 层炸成 500：
        // 业务入口（fromDfe）会再次解析并按 GraphQL 错误格式返回 400000。
        val throwingParser = mock<RequestParser>().also {
            whenever(it.parseMeta(any())).thenThrow(ApiError(ErrorCode.INVALID_REQUEST, "invalid x-req-meta"))
        }
        val res = MockHttpServletResponse()
        val handler = object : jakarta.servlet.http.HttpServlet() {
            override fun service(rq: jakarta.servlet.ServletRequest, rs: jakarta.servlet.ServletResponse) {
                rs.writer.write("ok")
            }
        }
        val req = MockHttpServletRequest("POST", "/customer/core/gql")
        RequestLoggingFilter(throwingParser).doFilter(req, res, MockFilterChain(handler))
        assertThat(res.status).isEqualTo(200)
        assertThat(res.contentAsString).isEqualTo("ok")
        // rid 仍已建立（生成值；MDC 在 filter 出口已清，用 request attribute 验证）
        assertThat(LogContext.requestId(req)).isNotNull()
    }
}
