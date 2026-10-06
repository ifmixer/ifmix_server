package com.ifmix.core.api.infra.http

import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper

/**
 * 通过模拟 filter chain 验证 GraphQlHttpStatusFilter 端到端改写 HTTP status。
 * 复现线上现象：body 带 errors code 400000，但 status 仍 200。
 */
class GraphQlHttpStatusFilterChainTest {

    private val filter = GraphQlHttpStatusFilter(JsonMapper.builder().build())

    @Test
    fun `error body 400000 sets status 400 on greq path`() {
        val req = MockHttpServletRequest("POST", "/customer/core/greq/m_media_media_presignUpload")
        val res = MockHttpServletResponse()

        // 模拟 GraphQL handler：写 200 + error body
        val chain = FilterChain { _, response ->
            response as jakarta.servlet.http.HttpServletResponse
            response.status = 200
            response.contentType = "application/json"
            response.writer.write(
                """{"errors":[{"message":"boom","extensions":{"code":"400000","errorName":"INVALID_REQUEST"}}],"data":null}""",
            )
        }

        filter.doFilter(req, res, chain)

        assertThat(res.status).isEqualTo(400)
        assertThat(res.contentAsString).contains("400000")
    }

    @Test
    fun `success body keeps 200`() {
        val req = MockHttpServletRequest("POST", "/customer/core/greq/q_auth_session_me")
        val res = MockHttpServletResponse()
        val chain = FilterChain { _, response ->
            response as jakarta.servlet.http.HttpServletResponse
            response.status = 200
            response.writer.write("""{"data":{"q_auth_session_me":{"id":"x"}}}""")
        }
        filter.doFilter(req, res, chain)
        assertThat(res.status).isEqualTo(200)
    }

    /** 复现：RequestLoggingFilter 作为 INNER filter（自己也 ContentCachingResponseWrapper + copyBodyToResponse）。 */
    @Test
    fun `works when an inner filter also wraps and copies body`() {
        val req = MockHttpServletRequest("POST", "/customer/core/greq/m_media_media_presignUpload")
        val res = MockHttpServletResponse()

        // inner filter：模拟 RequestLoggingFilter —— 包一层 ContentCachingResponseWrapper 并 copyBodyToResponse
        val chain = FilterChain { request, response ->
            val innerWrap = org.springframework.web.util.ContentCachingResponseWrapper(
                response as jakarta.servlet.http.HttpServletResponse,
            )
            innerWrap.status = 200
            innerWrap.writer.write(
                """{"errors":[{"message":"boom","extensions":{"code":"400000"}}],"data":null}""",
            )
            innerWrap.copyBodyToResponse()
        }

        filter.doFilter(req, res, chain)

        assertThat(res.status).`as`("outer filter should still override to 400").isEqualTo(400)
    }
}
