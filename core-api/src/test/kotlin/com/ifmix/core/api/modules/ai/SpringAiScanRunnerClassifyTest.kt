package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ifmix.core.api.modules.ai.service.KeyFailure
import com.ifmix.core.api.modules.ai.service.SpringAiScanRunner
import com.openai.core.http.Headers
import com.openai.errors.PermissionDeniedException
import com.openai.errors.UnauthorizedException
import com.openai.models.ErrorObject
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException

/**
 * 扫描 runner 的异常分类纯函数单测（cause 链遍历 + message 兜底边界 + 类型化失效 key 判定）。
 * 锁住三个关键行为：Spring AI 包装异常时仍可识别；纯数字（如 request id "40312"）不误判为 key 失效；
 * 仅**类型化** 401/403 置 typedInvalidKey=true（唯一触发永久禁用的判定），message 兜底命中恒为 false。
 */
class SpringAiScanRunnerClassifyTest {

    /** (rateLimited, invalidKey, timeout, other) = (300, 3600, 300, 30)，与 yml 默认一致便于核对。 */
    private fun classify(e: Exception) = SpringAiScanRunner.classify(e, 300, 3600, 300, 30)

    @Test
    fun `rate limit by message`() {
        assertThat(classify(Exception("HTTP 429 too many requests")))
            .isEqualTo(KeyFailure(300, "429"))
    }

    @Test
    fun `typed 401 via UnauthorizedException in wrapped cause chain`() {
        // Spring AI 可能包装 SDK 异常：类型化 401 在 cause 链上 → typedInvalidKey=true（触发永久禁用）
        val unauth = UnauthorizedException.builder()
            .headers(Headers.builder().build())
            .build()
        assertThat(classify(IllegalStateException(unauth)))
            .isEqualTo(KeyFailure(3600, "401", typedInvalidKey = true))
    }

    @Test
    fun `typed 403 via PermissionDeniedException always has reason 403`() {
        val denied = PermissionDeniedException.builder()
            .headers(Headers.builder().build())
            .build()
        assertThat(classify(RuntimeException(denied)))
            .isEqualTo(KeyFailure(3600, "403", typedInvalidKey = true))
    }

    @Test
    fun `message fallback invalid key is not typed`() {
        // message 兜底命中（无类型化异常）→ 只冷却、不触发永久禁用
        assertThat(classify(IllegalStateException(Exception("unauthorized: invalid api key"))))
            .isEqualTo(KeyFailure(3600, "401"))
    }

    @Test
    fun `timeout detected via socket exception in chain`() {
        assertThat(classify(RuntimeException(SocketTimeoutException("connect timed out"))))
            .isEqualTo(KeyFailure(300, "timeout"))
    }

    @Test
    fun `numeric substring does not misclassify as invalid key`() {
        // request id 里的 40312 不得命中纯数字匹配（那会误冷却 1h、误禁用）
        assertThat(classify(Exception("request 40312 failed"))).isEqualTo(KeyFailure(30, "error"))
    }

    @Test
    fun `numeric substring does not misclassify as rate limit`() {
        // request id 里的 42917 同样不得命中纯数字匹配（会误冷却 300s）
        assertThat(classify(Exception("request 42917 failed"))).isEqualTo(KeyFailure(30, "error"))
    }

    @Test
    fun `generic error maps to other with short cooldown`() {
        assertThat(classify(Exception("boom"))).isEqualTo(KeyFailure(30, "error"))
    }
}
