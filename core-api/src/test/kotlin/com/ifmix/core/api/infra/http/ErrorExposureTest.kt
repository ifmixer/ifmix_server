package com.ifmix.core.api.infra.http

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

/** 线上（expose=false）5xx / 未预期异常不透出细节；4xx 消息原样返回。 */
class ErrorExposureTest {

    private val prod = GlobalExceptionHandler(exposeErrors = false)
    private val test = GlobalExceptionHandler(exposeErrors = true)

    private fun msgOf(resp: org.springframework.http.ResponseEntity<*>): String =
        (resp.body as GraphQlErrorBody).errors[0].message

    @Test
    fun `prod hides 5xx ApiError message`() {
        val resp = prod.handleApiError(ApiError(ErrorCode.AI_UNAVAILABLE, "All AI models exhausted"))
        assertThat(msgOf(resp)).isEqualTo(GENERIC_SERVER_ERROR_MESSAGE)
        assertThat(resp.headers.getFirst("Retry-After")).isEqualTo("60")
    }

    @Test
    fun `prod keeps 4xx ApiError message`() {
        assertThat(msgOf(prod.handleApiError(ApiError(ErrorCode.QUOTA_EXCEEDED, "scan quota exhausted"))))
            .isEqualTo("scan quota exhausted")
    }

    @Test
    fun `prod hides unhandled exception message`() {
        assertThat(msgOf(prod.handleGeneric(IllegalStateException("jdbc:postgresql://secret"))))
            .isEqualTo(GENERIC_SERVER_ERROR_MESSAGE)
    }

    @Test
    fun `test env exposes details`() {
        assertThat(msgOf(test.handleApiError(ApiError(ErrorCode.AI_UNAVAILABLE, "All AI models exhausted"))))
            .isEqualTo("All AI models exhausted")
        assertThat(msgOf(test.handleGeneric(IllegalStateException("boom")))).isEqualTo("boom")
    }
}
