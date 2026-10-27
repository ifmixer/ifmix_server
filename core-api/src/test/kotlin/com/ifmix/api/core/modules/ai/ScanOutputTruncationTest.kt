package com.ifmix.core.api.modules.ai

import com.ifmix.core.api.modules.ai.service.SpringAiScanRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 截断判定启发式（coin 设计 §8.2.1）：解析失败后用于区分「截断」与「坏输出」。 */
class ScanOutputTruncationTest {

    private fun truncated() = SpringAiScanRunner.isLikelyTruncatedJson("")
    private fun likely(content: String) = SpringAiScanRunner.isLikelyTruncatedJson(content)

    @Test
    fun `complete json is not truncation`() {
        assertFalse(likely("""{"basic_result":{"object_overview":{"name":"袁大头"}}}"""))
        assertFalse(likely("""{"a":[1,2,3],"b":{"c":"d"}}"""))
    }

    @Test
    fun `unbalanced braces or brackets are truncation`() {
        assertTrue(likely("""{"basic_result":{"object_overview":{"name":"x"}"""))
        assertTrue(likely("""{"basic_result": {"specs": [1, 2"""))
        assertTrue(likely("""{"basic_result": {"valuation": {"price_min": 10"""))
    }

    @Test
    fun `code fences are stripped before checking`() {
        assertTrue(likely("```json\n{\"basic_result\": {\"a\": 1}\n"))
        assertFalse(likely("```\n{\"a\": {\"b\": 2}}\n```"))
    }

    @Test
    fun `no leading brace or empty content is not treated as truncation`() {
        // 无 JSON 起始（纯文本/空）——不是「截断」的形状，按普通坏输出处理
        assertFalse(likely(""))
        assertFalse(likely("The coin is unidentified."))
        assertFalse(truncated())
    }

    @Test
    fun `trailing garbage after closing brace still counts as complete`() {
        // lastIndexOf('}') 截取场景:parseJsonToMap 的截取窗口内是平衡的 → 不判截断
        assertFalse(likely("""{"a":1}" ... """))
    }
}
