package com.ifmix.core.api.infra.http

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * GraphQlHttpStatusFilter.statusFromBody 解析单测。
 * 覆盖：有 error code / 无 errors / 空 errors / 无 code / 非法 code / 空 body。
 */
class GraphQlHttpStatusFilterTest {

    private val filter = GraphQlHttpStatusFilter(JsonMapper.builder().build())

    private fun status(body: String): Int? {
        val m = GraphQlHttpStatusFilter::class.java
            .getDeclaredMethod("statusFromBody", ByteArray::class.java)
            .apply { isAccessible = true }
        return m.invoke(filter, body.toByteArray()) as Int?
    }

    @Test fun `first error code first 3 digits become status`() {
        assertThat(status("""{"errors":[{"extensions":{"code":"401000"}}]}""")).isEqualTo(401)
        assertThat(status("""{"errors":[{"extensions":{"code":"403000"}}]}""")).isEqualTo(403)
        assertThat(status("""{"errors":[{"extensions":{"code":"429001"}}]}""")).isEqualTo(429)
    }

    @Test fun `only first error is used`() {
        assertThat(status("""{"errors":[{"extensions":{"code":"404000"}},{"extensions":{"code":"500000"}}]}"""))
            .isEqualTo(404)
    }

    @Test fun `no errors returns null (keep 200)`() {
        assertThat(status("""{"data":{"q_auth_me":{"id":"x"}}}""")).isNull()
    }

    @Test fun `empty errors array returns null`() {
        assertThat(status("""{"errors":[]}""")).isNull()
    }

    @Test fun `error without extensions code returns null`() {
        assertThat(status("""{"errors":[{"message":"boom"}]}""")).isNull()
    }

    @Test fun `non-numeric or short code returns null`() {
        assertThat(status("""{"errors":[{"extensions":{"code":"ab"}}]}""")).isNull()
        assertThat(status("""{"errors":[{"extensions":{"code":"xxxyyy"}}]}""")).isNull()
    }

    @Test fun `empty body returns null`() {
        assertThat(status("")).isNull()
    }
}
