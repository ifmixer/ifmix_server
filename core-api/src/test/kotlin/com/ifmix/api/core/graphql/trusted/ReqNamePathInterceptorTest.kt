package com.ifmix.core.api.infra.graphql.trusted

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ReqNamePathInterceptor 的 reqName 解析单测。
 * 覆盖：GReq 命中末段 / 尾随斜杠 / 多余段只取首段 / GQL 无 greq 段 / greq 无后缀。
 */
class ReqNamePathInterceptorTest {

    // extractReqName 是 private；用反射调最省事，避免为测试放开可见性。
    private fun extract(path: String): String? {
        val m = ReqNamePathInterceptor::class.java
            .getDeclaredMethod("extractReqName", String::class.java)
            .apply { isAccessible = true }
        return m.invoke(ReqNamePathInterceptor(), path) as String?
    }

    @Test
    fun `extracts reqName from greq path`() {
        assertThat(extract("/customer/core/greq/q_ai_scan_getMyById")).isEqualTo("q_ai_scan_getMyById")
    }

    @Test
    fun `trailing slash tolerated`() {
        assertThat(extract("/customer/core/greq/q_auth_session_me/")).isEqualTo("q_auth_session_me")
    }

    @Test
    fun `only first segment after greq is taken`() {
        assertThat(extract("/customer/core/greq/q_auth_session_me/extra")).isEqualTo("q_auth_session_me")
    }

    @Test
    fun `gql path has no reqName`() {
        assertThat(extract("/customer/core/gql")).isNull()
    }

    @Test
    fun `greq without suffix has no reqName`() {
        assertThat(extract("/customer/core/greq/")).isNull()
        assertThat(extract("/customer/core/greq")).isNull()
    }
}
