package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ifmix.core.api.modules.ai.service.SpringAiScanRunner
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException

/**
 * 扫描 runner 的异常分类纯函数单测（cause 链遍历 + message 兜底边界）。
 * 锁住两个关键行为：Spring AI 包装异常时仍可识别；纯数字（如 request id "40312"）不误判为 key 失效。
 */
class SpringAiScanRunnerClassifyTest {

    /** (rateLimited, invalidKey, timeout, other) = (300, 3600, 300, 30)，与 yml 默认一致便于核对。 */
    private fun classify(e: Exception) = SpringAiScanRunner.classify(e, 300, 3600, 300, 30)

    @Test
    fun `rate limit by message`() {
        assertThat(classify(Exception("HTTP 429 too many requests"))).isEqualTo(300L to "429")
    }

    @Test
    fun `invalid key detected through wrapped cause chain`() {
        // Spring AI 可能包装 SDK 异常：类型/message 都在 cause 链上
        assertThat(classify(IllegalStateException(Exception("unauthorized: invalid api key"))))
            .isEqualTo(3600L to "401")
    }

    @Test
    fun `timeout detected via socket exception in chain`() {
        assertThat(classify(RuntimeException(SocketTimeoutException("connect timed out"))))
            .isEqualTo(300L to "timeout")
    }

    @Test
    fun `numeric substring does not misclassify as invalid key`() {
        // request id 里的 40312 不得命中纯数字匹配（那会误冷却 1h）
        assertThat(classify(Exception("request 40312 failed"))).isEqualTo(30L to "error")
    }

    @Test
    fun `numeric substring does not misclassify as rate limit`() {
        // request id 里的 42917 同样不得命中纯数字匹配（会误冷却 300s）
        assertThat(classify(Exception("request 42917 failed"))).isEqualTo(30L to "error")
    }

    @Test
    fun `generic error maps to other with short cooldown`() {
        assertThat(classify(Exception("boom"))).isEqualTo(30L to "error")
    }
}
