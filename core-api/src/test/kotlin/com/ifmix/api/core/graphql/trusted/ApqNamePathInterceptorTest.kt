package com.ifmix.core.api.infra.graphql.trusted

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ApqNamePathInterceptor 的 apqName 解析单测。
 * 覆盖：APQ 命中末段 / 尾随斜杠 / 多余段只取首段 / GQL 无 apq 段 / apq 无后缀。
 */
class ApqNamePathInterceptorTest {

    // extractApqName 是 private；用反射调最省事，避免为测试放开可见性。
    private fun extract(path: String): String? {
        val m = ApqNamePathInterceptor::class.java
            .getDeclaredMethod("extractApqName", String::class.java)
            .apply { isAccessible = true }
        return m.invoke(ApqNamePathInterceptor(), path) as String?
    }

    @Test
    fun `extracts apqName from apq path`() {
        assertThat(extract("/customer/core/apq/q_ai_findMyScanById")).isEqualTo("q_ai_findMyScanById")
    }

    @Test
    fun `trailing slash tolerated`() {
        assertThat(extract("/customer/core/apq/q_auth_me/")).isEqualTo("q_auth_me")
    }

    @Test
    fun `only first segment after apq is taken`() {
        assertThat(extract("/customer/core/apq/q_auth_me/extra")).isEqualTo("q_auth_me")
    }

    @Test
    fun `gql path has no apqName`() {
        assertThat(extract("/customer/core/gql")).isNull()
    }

    @Test
    fun `apq without suffix has no apqName`() {
        assertThat(extract("/customer/core/apq/")).isNull()
        assertThat(extract("/customer/core/apq")).isNull()
    }
}
