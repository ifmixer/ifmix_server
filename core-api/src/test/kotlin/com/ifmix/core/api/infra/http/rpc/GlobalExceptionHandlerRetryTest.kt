package com.ifmix.core.api.infra.http.rpc

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.GlobalExceptionHandler
import org.junit.jupiter.api.Test

/**
 * [GlobalExceptionHandler.handleApiError] 的 Retry-After 头行为（S2 §3.7）：
 * - retryAfterSec 非空 → 响应头 Retry-After = 该值（429 限流类 / 503 降级）；
 * - 无 retryAfterSec 且非 AI_UNAVAILABLE → 无 Retry-After 头；
 * - AI_UNAVAILABLE → 固定 Retry-After: 60；retryAfterSec 同时非空时 retryAfterSec 优先（追加行在 AI_UNAVAILABLE 行之后的实现顺序锁定）。
 */
class GlobalExceptionHandlerRetryTest {

    private val handler = GlobalExceptionHandler(exposeErrors = true)

    private fun retryAfter(ex: ApiError): String? =
        handler.handleApiError(ex).headers.getFirst("Retry-After")

    @Test
    fun `api error with retry after sec sets retry after header`() {
        val ex = ApiError(ErrorCode.RATE_LIMITED, "slow down", retryAfterSec = 120)
        assertThat(retryAfter(ex)).isEqualTo("120")
    }

    @Test
    fun `api error without retry after sec has no retry after header`() {
        val ex = ApiError(ErrorCode.INVALID_REQUEST, "bad input")
        assertThat(retryAfter(ex)).isNull()
    }

    @Test
    fun `ai unavailable keeps fixed retry after 60`() {
        val ex = ApiError(ErrorCode.AI_UNAVAILABLE, "All AI models exhausted")
        assertThat(retryAfter(ex)).isEqualTo("60")
    }

    @Test
    fun `retry after sec wins over ai unavailable fixed 60`() {
        val ex = ApiError(ErrorCode.AI_UNAVAILABLE, "All AI models exhausted", retryAfterSec = 300)
        assertThat(retryAfter(ex)).isEqualTo("300")
    }

    @Test
    fun `daily limited error with retry after sec sets header`() {
        // 规格 §4.4：429002 必带 retryAfterSec=到 UTC 零点秒数
        val ex = ApiError(ErrorCode.INSTALL_DAILY_LIMITED, "install daily limit", retryAfterSec = 43_200)
        assertThat(retryAfter(ex)).isEqualTo("43200")
    }
}
