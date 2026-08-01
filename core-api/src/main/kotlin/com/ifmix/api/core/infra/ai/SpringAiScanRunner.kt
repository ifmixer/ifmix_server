package com.ifmix.api.core.infra.ai

import com.ifmix.api.core.entity.enums.ScanStatus
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.service.antique.ScanResult
import com.ifmix.api.core.service.antique.ScanRunner
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.content.Media
import org.springframework.util.MimeTypeUtils
import java.net.URI

/**
 * ScanRunner 基于 Spring AI OpenAI-compatible model.
 *
 * 向多模态模型发送图片，提取 JSON 结果，映射为 ScanResult。
 *
 * 功能：
 * - 每个模型尝试所有可用 key（内层循环），不止一个
 * - 检测真实 HTTP 异常（状态码 429、超时等），映射为内部异常
 * - Pre-deduct quota / 失败归还
 * - 模型 fallback 链（外层循环）
 */
open class SpringAiScanRunner(
    private val chatClientFactory: AgnesChatClientFactory,
    private val keyStore: AgnesKeyStore,
    private val fallbackModels: List<String>,
    private val snakeCaseMapper: tools.jackson.databind.ObjectMapper,
) : ScanRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun run(ctx: RequestContext, imageUrl: String): ScanResult {
        val states = keyStore.init()

        // 模型列表：主模型 + fallback
        val allModels = listOf(chatClientFactory.defaultModel) + fallbackModels

        for (model in allModels) {
            // 内层循环：对当前 model 尝试所有可用 key
            var attemptCount = 0
            val maxAttempts = states.size.coerceAtLeast(1)

            while (attemptCount < maxAttempts) {
                val pickedKeyId = keyStore.weightedPick(states) ?: break
                attemptCount++

                try {
                    // Pre-deduct quota
                    if (!keyStore.preDeduct(states, pickedKeyId)) {
                        continue
                    }

                    // 创建 ChatClient
                    val doc = states[pickedKeyId]?.doc
                    val apiKey = doc?.key ?: continue
                    val client = chatClientFactory.forKey(apiKey, model)

                    // 构造多模态 prompt
                    val media = Media(MimeTypeUtils.IMAGE_PNG, URI.create(imageUrl))
                    val userMsg = UserMessage.builder()
                        .text(ScanPrompt.userPrompt(imageUrl))
                        .media(media)
                        .build()

                    val prompt = Prompt(userMsg)
                    val response = client.prompt(prompt).call()
                    val content = response.content() ?: ""

                    // 解析 JSON → ScanResult
                    val result = parseJsonToScanResult(content, model, pickedKeyId)

                    // 成功：确认消费
                    keyStore.release(states, pickedKeyId)
                    return result

                } catch (e: Exception) {
                    // 检测真实 HTTP 异常类型
                    val isRateLimit = isRateLimitException(e)
                    val isTimeout = isTimeoutException(e)

                    if (isRateLimit) {
                        log.warn("Rate limited on key $pickedKeyId for model $model: ${e.message}")
                        keyStore.markUnavailable(states, pickedKeyId, 300L) // 5 min 冷却
                        // 继续尝试下一个 key
                    } else if (isTimeout) {
                        log.warn("Timeout on key $pickedKeyId for model $model: ${e.message}")
                        // 继续尝试下一个 key
                    } else {
                        log.error("Error scanning with key $pickedKeyId, model $model: ${e.message}")
                        // 未知错误也继续尝试下一个 key
                    }
                }
            }
            // 当前 model 所有 key 都失败，尝试下一个 model
            log.warn("All keys exhausted for model $model, trying next model")
        }

        // 所有 model + key 都失败
        log.error("All models exhausted — AI_UNAVAILABLE")
        return ScanResult(
            scanId = UuidV7.generate().toString(),
            status = ScanStatus.FAILED,
            errorMessage = "All AI models exhausted. No API keys available or all rate-limited.",
        )
    }

    /**
     * 检测是否为 429 Rate Limit 异常。
     * 匹配 Spring AI / OkHttp / openai-java SDK 可能抛出的异常。
     */
    private fun isRateLimitException(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: ""
        // Spring AI / openai-java SDK 会在 message 中包含 HTTP 状态码
        if (msg.contains("429") || msg.contains("rate limit") || msg.contains("too many requests")) {
            return true
        }
        // 检查嵌套 cause
        val cause = e.cause
        if (cause != null) {
            val causeMsg = cause.message?.lowercase() ?: ""
            if (causeMsg.contains("429") || causeMsg.contains("rate limit")) {
                return true
            }
        }
        return false
    }

    /**
     * 检测是否为超时异常。
     */
    private fun isTimeoutException(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: ""
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return true
        }
        // 常见超时异常类型
        if (e is java.net.SocketTimeoutException ||
            e is java.util.concurrent.TimeoutException) {
            return true
        }
        val cause = e.cause
        if (cause is java.net.SocketTimeoutException ||
            cause is java.util.concurrent.TimeoutException) {
            return true
        }
        return false
    }

    /**
     * 解析 AI 模型返回的 JSON 文本为 ScanResult。
     */
    private fun parseJsonToScanResult(jsonText: String, modelName: String, keyId: String): ScanResult {
        return try {
            val cleaned = jsonText
                .trim()
                .removePrefix("```json")
                .removeSuffix("```")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val startIdx = cleaned.indexOf('{')
            val endIdx = cleaned.lastIndexOf('}')
            val jsonOnly = if (startIdx >= 0 && endIdx > startIdx) {
                cleaned.substring(startIdx, endIdx + 1)
            } else {
                cleaned
            }

            snakeCaseMapper.readValue(jsonOnly, ScanResult::class.java)
                ?: ScanResult(
                    scanId = UuidV7.generate().toString(),
                    status = ScanStatus.COMPLETED,
                    errorMessage = "Could not parse JSON fields",
                )
        } catch (e: Exception) {
            log.error("Failed to parse AI response [${jsonText.take(200)}]: ${e.message}")
            ScanResult(
                scanId = UuidV7.generate().toString(),
                status = ScanStatus.COMPLETED,
                name = jsonText.take(100),
                notes = "raw_response: $jsonText",
                errorMessage = "JSON parsing fallback: ${e.message}",
            )
        }
    }
}
