package com.ifmix.core.api.infra.http

import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper

/**
 * GraphQlHttpStatusFilter 端到端单测（mock filter chain）：
 * - errors → 顶层注入 code/msg（errors 保留）+ extensions.code 前 3 位映射 HTTP status
 * - 成功响应 body 原样透传
 */
class GraphQlHttpStatusFilterTest {

    private val filter = GraphQlHttpStatusFilter(JsonMapper.builder().build())

    /** 模拟 GraphQL handler 返回 200 + 指定 body，跑完 filter 后返回最终响应。 */
    private fun run(path: String, body: String, handlerStatus: Int = 200): MockHttpServletResponse {
        val req = MockHttpServletRequest("POST", path)
        val res = MockHttpServletResponse()
        val chain = FilterChain { _, response ->
            response as jakarta.servlet.http.HttpServletResponse
            response.status = handlerStatus
            response.contentType = "application/json"
            response.writer.write(body)
        }
        filter.doFilter(req, res, chain)
        return res
    }

    @Test fun `error body gets top-level code and msg, errors kept, status mapped`() {
        val res = run(
            "/customer/core/gql",
            """{"errors":[{"message":"invalid token","extensions":{"code":"401000","errorName":"UNAUTHORIZED"}}],"data":null}""",
        )
        assertThat(res.status).isEqualTo(401)
        val root = JsonMapper.builder().build().readTree(res.contentAsString)
        assertThat(root.get("code").asString()).isEqualTo("401000")
        assertThat(root.get("msg").asString()).isEqualTo("invalid token")
        assertThat(root.get("errors")).isNotNull
        assertThat(root.get("data").isNull).isTrue()
    }

    @Test fun `only first error is used for code msg`() {
        val res = run(
            "/customer/core/gql",
            """{"errors":[{"message":"first","extensions":{"code":"404000"}},{"message":"second","extensions":{"code":"500000"}}]}""",
        )
        assertThat(res.status).isEqualTo(404)
        val root = JsonMapper.builder().build().readTree(res.contentAsString)
        assertThat(root.get("code").asString()).isEqualTo("404000")
        assertThat(root.get("msg").asString()).isEqualTo("first")
    }

    @Test fun `framework error without extensions code gets fallback code from classification`() {
        val res = run(
            "/customer/core/gql",
            """{"errors":[{"message":"Validation failed","extensions":{"classification":"ValidationError"}}],"data":null}""",
        )
        assertThat(res.status).isEqualTo(200) // 兜底 code 不参与 status 映射
        val root = JsonMapper.builder().build().readTree(res.contentAsString)
        assertThat(root.get("code").asString()).isEqualTo("400000")
        assertThat(root.get("msg").asString()).isEqualTo("Validation failed")
    }

    @Test fun `framework error without any classification falls back to 500000`() {
        val res = run("/customer/core/gql", """{"errors":[{"message":"boom"}]}""")
        val root = JsonMapper.builder().build().readTree(res.contentAsString)
        assertThat(root.get("code").asString()).isEqualTo("500000")
        assertThat(root.get("msg").asString()).isEqualTo("boom")
    }

    @Test fun `no errors body passes through untouched`() {
        val body = """{"data":{"q_auth_session_me":{"id":"x"}}}"""
        val res = run("/customer/core/gql", body)
        assertThat(res.status).isEqualTo(200)
        assertThat(res.contentAsString).isEqualTo(body)
    }

    @Test fun `empty errors array passes through untouched`() {
        val body = """{"errors":[],"data":null}"""
        val res = run("/customer/core/gql", body)
        assertThat(res.contentAsString).isEqualTo(body)
    }

    @Test fun `empty body passes through`() {
        val res = run("/customer/core/gql", "")
        assertThat(res.status).isEqualTo(200)
        assertThat(res.contentAsString).isEmpty()
    }

    @Test fun `unparseable body passes through`() {
        val res = run("/customer/core/gql", "not json")
        assertThat(res.contentAsString).isEqualTo("not json")
    }
}
