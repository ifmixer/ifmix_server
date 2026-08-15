package com.ifmix.api.core.modules.ai.service

import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.scan.dto.ScanInput
import com.ifmix.api.core.modules.scan.ScanRunner
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.content.Media
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import org.springframework.util.MimeTypeUtils
import tools.jackson.databind.ObjectMapper
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeoutException

/**
 * ScanRunner 基于 Spring AI OpenAI-compatible model.
 *
 * 成功返回 AI 解析的 JSON Map；失败抛 ApiError。
 */
@Service
@Primary
open class SpringAiScanRunner(
    private val chatClientFactory: AgnesChatClientFactory,
    private val keyStore: AgnesKeyStore,
    @Value("\${app.agnes.ai.modelFallbackOrder:}") fallbackOrderStr: String,
    @Qualifier("snakeCaseMapper") private val snakeCaseMapper: ObjectMapper,
) : ScanRunner {

    private val fallbackModels: List<String> = fallbackOrderStr
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(ctx: OperationContext, input: ScanInput): Map<String, Any?> {
        val states = keyStore.init()

        if (states.isEmpty()) {
            log.error("No Agnes API keys found in database — cannot run AI scan")
            throw ApiError(ErrorCode.AI_UNAVAILABLE, "No AI API keys configured")
        }
        log.debug("Loaded {} Agnes key(s) for scan", states.size)

        val allModels = listOf(chatClientFactory.defaultModel) + fallbackModels

        val mediaItems = input.items.map { item ->
            val mimeType = MimeTypeUtils.parseMimeType(item.mediaType)
            Media(mimeType, URI.create(item.imageUrl))
        }

        for (model in allModels) {
            var attemptCount = 0
            val maxAttempts = states.size.coerceAtLeast(1)

            while (attemptCount < maxAttempts) {
                val pickedKeyId = keyStore.weightedPick(states) ?: break
                attemptCount++

                try {
                    if (!keyStore.preDeduct(states, pickedKeyId)) {
                        continue
                    }

                    val doc = states[pickedKeyId]?.doc
                    val apiKey = doc?.key ?: continue
                    val client = chatClientFactory.forKey(apiKey, model)

                    val systemMsg = SystemMessage(ScanPrompt.systemPrompt(input))
                    val userText = ScanPrompt.userPrompt(input)
                    val userMsg = UserMessage.builder()
                        .text(userText)
                        .media(*mediaItems.toTypedArray())
                        .build()

                    val prompt = Prompt(listOf(systemMsg, userMsg))
                    log.debug("Scan run start. model={}, key={}, model, pickedKeyId)

                    val startMs = System.currentTimeMillis()
                    val response = client.prompt(prompt).call()
                    val content = response.content() ?: ""
                    val elapsedMs = System.currentTimeMillis() - startMs
                    log.debug("Scan run completed. model={}, key={}, elapsed={}ms, content={}", model, pickedKeyId, elapsedMs, content)

                    val data = parseJsonToMap(content)

                    keyStore.release(states, pickedKeyId)
                    return data

                } catch (e: Exception) {
                    if (isRateLimitException(e)) {
                        log.warn("Rate limited on key {} for model {}: {}", pickedKeyId, model, e.message)
                        keyStore.markUnavailable(states, pickedKeyId, 300L)
                    } else if (isTimeoutException(e)) {
                        log.warn("Timeout on key {} for model {}: {}", pickedKeyId, model, e.message)
                    } else {
                        log.error("Error scanning with key {} for model {}", pickedKeyId, model, e)
                    }
                }
            }
            log.warn("All keys exhausted for model $model, trying next model")
        }

        log.error("All models exhausted — AI_UNAVAILABLE")
        throw ApiError(ErrorCode.AI_UNAVAILABLE, "All AI models exhausted")
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJsonToMap(jsonText: String): Map<String, Any?> {
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

        val map = snakeCaseMapper.readValue(jsonOnly, Map::class.java) as? Map<String, Any?>
            ?: throw ApiError(ErrorCode.AI_UNAVAILABLE, "AI returned non-JSON response")
        return map
    }

    private fun isRateLimitException(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: ""
        if (msg.contains("429") || msg.contains("rate limit") || msg.contains("too many requests")) return true
        val causeMsg = e.cause?.message?.lowercase() ?: ""
        if (causeMsg.contains("429") || causeMsg.contains("rate limit")) return true
        return false
    }

    private fun isTimeoutException(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: ""
        if (msg.contains("timeout") || msg.contains("timed out")) return true
        if (e is SocketTimeoutException || e is TimeoutException) return true
        val cause = e.cause
        if (cause is SocketTimeoutException || cause is TimeoutException) return true
        return false
    }
}
