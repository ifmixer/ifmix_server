package com.ifmix.api.core.common.ai

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.ai.ScanResult
import com.ifmix.api.core.modules.antique.ScanRunner
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.content.Media
import org.springframework.util.MimeTypeUtils
import java.net.URI

/**
 * ScanRunner berbasis Spring AI OpenAI-compatible model.
 *
 * Mengirim gambar ke model multimodal (GPT-4o, Claude Vision, dll),
 * mengekstrak JSON hasil, dan memetakan ke ScanResult.
 *
 * Fitur:
 * - Try-fallback model otomatis (utama → daftar fallback)
 * - Deteksi 429 / timeout via exception heuristic
 * - Pre-deduct quota sebelum setiap attempt
 * - Return quota jika gagal (rate limit / timeout)
 */
open class SpringAiScanRunner(
    private val chatClientFactory: AgnesChatClientFactory,
    private val keyStore: AgnesKeyStore,
    private val fallbackModels: List<String>,
) : ScanRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Eksekusi scan terhadap gambar di imageUrl.
     *
     * Alur:
     * 1. Load keys dari store
     * 2. Untuk setiap model (utama + fallback):
     *    a. Pick key via weighted random
     *    b. Pre-deduct quota
     *    c. Kirim prompt multimodal
     *    d. Parse JSON response → ScanResult
     *    e. Jika sukses: release quota, return result
     *    f. Jika rate-limited: mark unavailable, retry dengan key lain
     *    g. Jika timeout: release pre-deduct, coba model berikutnya
     */
    override suspend fun run(ctx: RequestContext, imageUrl: String): ScanResult {
        val states = keyStore.init()

        // Daftar model: utama (dari factory config) + fallback
        val allModels = listOf(chatClientFactory.defaultModel) + fallbackModels

        for (model in allModels) {
            var lastError: String? = null

            // Coba semua key yang tersedia untuk model ini
            val pickedKeyId = keyStore.weightedPick(states) ?: break

            try {
                // Pre-deduct quota
                if (!keyStore.preDeduct(states, pickedKeyId)) {
                    continue
                }

                // Buat ChatClient dengan key ini
                val doc = states[pickedKeyId]?.doc
                val apiKey = doc?.key ?: continue
                val client = chatClientFactory.forKey(apiKey, model)

                // Konstruksi prompt multimodal
                val media = Media(MimeTypeUtils.IMAGE_PNG, URI.create(imageUrl))
                val userMsg = UserMessage.builder()
                    .text(ScanPrompt.userPrompt(imageUrl))
                    .media(media)
                    .build()

                // Eksekusi ke model — wrap UserMessage in Prompt
                val prompt = Prompt(userMsg)
                val response = client.prompt(prompt).call()
                val content = response.content() ?: ""

                // Parse JSON → ScanResult
                val result = parseJsonToScanResult(content, model, pickedKeyId)

                // Sukses: release quota (confirm consumption)
                keyStore.release(states, pickedKeyId)
                return result

            } catch (e: RateLimitException) {
                // 429 detected — mark key unavailable, release pre-deduct
                log.warn("Rate limited on key $pickedKeyId for model $model")
                keyStore.markUnavailable(states, pickedKeyId, 300L) // 5 min cooling
                lastError = "rate_limited"
            } catch (e: TimeoutException) {
                // Timeout — release pre-deduct, retry next key
                log.warn("Timeout on key $pickedKeyId for model $model")
                lastError = "timeout"
            } catch (e: Exception) {
                // Generic error — release pre-deduct, retry next key
                log.error("Error scanning with key $pickedKeyId, model $model: ${e.message}")
                lastError = e.message ?: "unknown_error"
            }
        }

        // Semua model + key sudah dicoba, tidak ada yang berhasil
        log.error("All models exhausted — AI_UNAVAILABLE")
        return ScanResult(
            scanId = ctx.installId ?: java.util.UUID.randomUUID().toString(),
            status = ScanResult.Status.FAILED,
            errorMessage = "All AI models exhausted. No API keys available or all rate-limited.",
        )
    }

    /**
     * Parse teks JSON dari model AI menjadi objek ScanResult.
     *
     * Menggunakan Jackson ObjectMapper untuk parsing snake_case → camelCase.
     * Fallback ke manual mapping jika parsing gagal.
     */
    private fun parseJsonToScanResult(jsonText: String, modelName: String, keyId: String): ScanResult {
        return try {
            // Simple extraction: look for JSON block in response
            val cleaned = jsonText
                .trim()
                .removePrefix("```json")
                .removeSuffix("```")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            // Try to find JSON object boundaries
            val startIdx = cleaned.indexOf('{')
            val endIdx = cleaned.lastIndexOf('}')
            val jsonOnly = if (startIdx >= 0 && endIdx > startIdx) {
                cleaned.substring(startIdx, endIdx + 1)
            } else {
                cleaned
            }

            // Use Jackson JsonMapper to parse snake_case → camelCase
            val mapper = tools.jackson.databind.json.JsonMapper.builder()
                .addModule(tools.jackson.module.kotlin.KotlinModule.Builder().build())
                .propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
                .build()

            mapper.readValue(jsonOnly, ScanResult::class.java)
                ?.let { it.copy(modelName = modelName, apiKeyId = keyId.takeLast(8)) }
                ?: ScanResult(
                    scanId = java.util.UUID.randomUUID().toString(),
                    status = ScanResult.Status.COMPLETED,
                    errorMessage = "Could not parse JSON fields",
                    modelName = modelName,
                    apiKeyId = keyId.takeLast(8),
                )
        } catch (e: Exception) {
            log.error("Failed to parse AI response [${jsonText.take(200)}]: ${e.message}")
            ScanResult(
                scanId = java.util.UUID.randomUUID().toString(),
                status = ScanResult.Status.COMPLETED,
                name = jsonText.take(100),
                notes = "raw_response: $jsonText",
                errorMessage = "JSON parsing fallback: ${e.message}",
                modelName = modelName,
                apiKeyId = keyId.takeLast(8),
            )
        }
    }

    /**
     * Custom exception untuk rate limiting (429).
     */
    class RateLimitException(message: String) : RuntimeException(message)

    /**
     * Custom exception untuk timeout.
     */
    class TimeoutException(message: String) : RuntimeException(message)
}
