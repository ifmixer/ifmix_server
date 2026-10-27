package com.ifmix.core.api.modules.ai.service

import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.dto.ai.ScanInput
import com.ifmix.core.api.dto.ai.ScanType
import com.ifmix.core.api.modules.ai.ScanRunner
import com.openai.errors.RateLimitException
import com.openai.errors.PermissionDeniedException
import com.openai.errors.UnauthorizedException
import com.openai.errors.BadRequestException
import com.openai.errors.UnprocessableEntityException
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
 * 异常分类结果（设计见 docs/design/ai/api-key-disable-and-probe-skip.md）：
 * 冷却秒数 + 冷却原因（"429"/"401"/"403"/"timeout"/"error"，写 Redis value 与日志）
 * + [typedInvalidKey]——是否由**类型化** 401/403 异常判定（cause 链含
 * [UnauthorizedException] / [PermissionDeniedException]）。
 *
 * 分层判定：类型化异常是 API 确实 401/403 的强信号 → 冷却 **+ 永久禁用**（不可逆，需人工恢复）；
 * message 兜底短语可能被网关自定义错误页 / SDK 包装 message 误中 → 仅冷却（1h 自动解冻）。
 * 不可逆操作只走高置信度路径。
 */
internal data class KeyFailure(
    val cooldownSec: Long,
    val reason: String,
    val typedInvalidKey: Boolean = false,
)

/**
 * ScanRunner 基于 Spring AI OpenAI-compatible model。
 *
 * key 池交互（provider 无关，设计见 docs/design/ai/api-key-pool.md）：
 * - 每次尝试从 [AiApiKeyStore.pick] 轮询取 key（候选全冷却返回 null，计为一次 attempt）；
 * - 失败按类型冷却（时长见 app.ai.apikey-pool.cooldown.*）：429→300s、401/403（key 失效）→1h、
 *   超时→300s、其他→30s；**类型化** 401/403（cause 链含 Unauthorized/PermissionDenied）额外永久禁用
 *   （PG enabled=false，不可逆）；message 兜底命中仅冷却；JSON 解析失败不冷却（模型输出问题，非 key 问题），
 *   attempt 已消耗、游标已推进；
 * - 单模型重试上界 [MAX_ATTEMPTS_PER_MODEL]，与 key 池大小解耦；总预算 [scanDeadlineSec] 兜底最坏等待。
 *
 * 成功返回 AI 解析的 JSON Map；失败抛 ApiError。
 */
@Service
@Primary
open class SpringAiScanRunner(
    private val chatClientFactory: AiChatClientFactory,
    private val keyStore: AiApiKeyStore,
    @Value("\${app.ai.model-fallback-order:}") fallbackOrderStr: String,
    @Value("\${app.ai.apikey-pool.cooldown.rate-limited-sec:300}") private val cooldownRateLimitedSec: Long,
    @Value("\${app.ai.apikey-pool.cooldown.invalid-key-sec:3600}") private val cooldownInvalidKeySec: Long,
    @Value("\${app.ai.apikey-pool.cooldown.timeout-sec:300}") private val cooldownTimeoutSec: Long,
    @Value("\${app.ai.apikey-pool.cooldown.other-sec:30}") private val cooldownOtherSec: Long,
    /**
     * 单次扫描总预算（秒，跨模型/attempt 的墙钟上限），超限抛 AI_UNAVAILABLE。
     * 扫描在 GraphQL 请求内同步执行——没有它，连续超时最坏拖 4×6min×模型数，客户端早已断开而后端还在烧 key。
     */
    @Value("\${app.ai.scan-deadline-sec:600}") private val scanDeadlineSec: Long,
    @Qualifier("snakeCaseMapper") private val snakeCaseMapper: ObjectMapper,
    /** 提示词按 projectId 选择（设计 §8.1）；这里不再持有单个 ScanPrompt。 */
    private val scanPromptRegistry: ScanPromptRegistry,
) : ScanRunner {

    private val fallbackModels: List<String> = fallbackOrderStr
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }

    private val log = LoggerFactory.getLogger(javaClass)

    init {
        // deadline ≤ call-timeout 时每次扫描都会在首次尝试前被预算检查拦下，静默全量 AI_UNAVAILABLE——启动即失败
        require(scanDeadlineSec > chatClientFactory.callTimeoutSec) {
            "app.ai.scan-deadline-sec ($scanDeadlineSec) must exceed app.ai.call-timeout-sec " +
                "(${chatClientFactory.callTimeoutSec})"
        }
    }

    companion object {
        /** 单模型最大尝试次数（换 key 重试的上界）。与 key 数解耦——否则 key 池一大，坏 key 会被原样重试上千次。 */
        private const val MAX_ATTEMPTS_PER_MODEL = 4

        /**
         * provider 回源取图失败时的换 key 重试上界（见 [isTransientImageFetchFailure]）。
         * 比 [MAX_ATTEMPTS_PER_MODEL] 更小：这类失败 provider 侧要先跑完图片下载超时才返回，
         * 单次可能占几十秒，重试上界放满会把 deadline 耗光却仍失败。
         * 2 次 = 原 key 失败后再换一个 key 试一次，够覆盖偶发抖动，也留得下时间走正常分类路径。
         */
        private const val MAX_IMAGE_FETCH_ATTEMPTS = 2

        /** cause 链遍历深度上限（防理论上的环）。Spring AI 可能包装 SDK 异常，类型判定必须沿链下探。 */
        private const val MAX_CAUSE_DEPTH = 16

        /**
         * 异常 → [KeyFailure]。类型优先、message 兜底——message 兜底**不含纯数字匹配**
         * （"429" in m 会把 request id "42917" 误判成限流、"40312" 误判成 key 失效），沿 cause 链下探。
         * 纯函数（冷却时长入参），供单测锁定分类行为。
         */
        internal fun classify(
            e: Exception,
            rateLimitedSec: Long,
            invalidKeySec: Long,
            timeoutSec: Long,
            otherSec: Long,
        ): KeyFailure = when {
            isRateLimitException(e) -> KeyFailure(rateLimitedSec, "429")
            isInvalidKeyException(e) -> {
                val typed = chainOf(e).any { it is UnauthorizedException || it is PermissionDeniedException }
                // 类型化判定（typed）命中即 key 确实 401/403 → 永久禁用；仅 message 兜底命中则只冷却。
                KeyFailure(
                    invalidKeySec,
                    if (chainOf(e).any { it is PermissionDeniedException }) "403" else "401",
                    typedInvalidKey = typed,
                )
            }
            isTimeoutException(e) -> KeyFailure(timeoutSec, "timeout")
            else -> KeyFailure(otherSec, "error")
        }

        /**
         * 异常 cause 链遍历：调用走 Spring AI 的 ChatClient，openai-java 的类型化异常
         * 可能被包装后再抛出——类型与 message 判定都必须沿链下探，不能只看最外层。
         */
        private fun chainOf(e: Exception): Sequence<Throwable> =
            generateSequence(e as Throwable) { it.cause }.take(MAX_CAUSE_DEPTH)

        private fun isRateLimitException(e: Exception): Boolean =
            chainOf(e).any {
                it is RateLimitException ||
                    it.message.orEmpty().lowercase().let { m ->
                        "rate limit" in m || "too many requests" in m
                    }
            }

        /** 401/403（key 失效）：类型化异常优先，短语兜底（不用纯数字匹配）。 */
        private fun isInvalidKeyException(e: Exception): Boolean =
            chainOf(e).any {
                it is UnauthorizedException || it is PermissionDeniedException ||
                    it.message.orEmpty().lowercase().let { m ->
                        "unauthorized" in m || "invalid api key" in m ||
                            "forbidden" in m || "permission denied" in m
                    }
            }

        private fun isTimeoutException(e: Exception): Boolean =
            chainOf(e).any {
                it is SocketTimeoutException || it is TimeoutException ||
                    it.message.orEmpty().lowercase().let { m ->
                        "timeout" in m || "timed out" in m
                    }
            }

        /**
         * 不可重试的请求错误（400 BadRequest / 422 UnprocessableEntity）：请求数据本身有问题
         * （如坏图片、图片加载失败、prompt 非法），换 key 重试毫无意义——同样数据在任何 key 上都失败。
         * 命中则立即终止：不冷却 key（key 没问题）、不重试、不消耗剩余 attempt。
         * 仅类型判定，不做 message 纯数字兜底（避免 request id 含 "400" 误判）。
         */
        internal fun isNonRetryableRequestError(e: Exception): Boolean =
            chainOf(e).any { it is BadRequestException || it is UnprocessableEntityException }

        /**
         * provider 回源取图失败（**瞬态**）——在 400/422 里单独识别出来。
         *
         * 背景：图片以 URL 形式交给 provider，由 provider 自己去我们的对象存储回源下载。
         * 源站抖动/跨境网络超时会让 provider 在下发前就拿不到图，此时它返回的是 **400**
         * （实测 agnes-3.0-flash 常见，2.5 少见）：
         * `"400: …BadRequestError: OpenAIException - {"message":"An exception occurred while
         *   loading IMAGE data at index 0: … Timed out while downloading media URL: …"}"`
         * 这不是"图片坏"也不是"key 坏"——**同样的 URL、同样的 key，稍后重试往往就能过**，
         * 所以归入可重试，走 [MAX_IMAGE_FETCH_ATTEMPTS] 次换 key 重试。
         */
        internal fun isTransientImageFetchFailure(e: Exception): Boolean {
            if (!isNonRetryableRequestError(e)) return false
            return chainOf(e).any {
                it.message.orEmpty().lowercase().let { m ->
                    "loading image data" in m ||
                        "error while loading data" in m ||
                        "downloading media url" in m ||
                        "failed to download image" in m
                }
            }
        }
    }

    /**
     * 输出是否**疑似被截断**（设计 §8.2.1）。
     *
     * JSON_OBJECT 模式下截断表现为「JSON 到 maxTokens 处戛然而止」：花括号/方括号不平衡。
     * 解析失败 + 本判定命中 → 走 basic 模板降级重跑，而不是按坏输出换 key 重试整条
     * （同样的输出规模换个 key 大概率还是截断，白烧一次调用）。
     * 纯启发式（JSON_OBJECT 下拿不到可靠的 finish_reason），宁可漏判（当普通失败重试）
     * 不可误判误伤完整输出——所以同时要求「解析已失败」才调用本函数。
     */
    internal fun isLikelyTruncatedJson(content: String): Boolean {
        val cleaned = content
            .trim()
            .removePrefix("```json")
            .removeSuffix("```")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val startIdx = cleaned.indexOf('{')
        if (startIdx < 0) return false
        val body = cleaned.substring(startIdx)
        // 括号不平衡（开多于闭）→ JSON 在输出中途被切断。粗粒度（不考虑字符串内括号），
        // 但本函数只在解析已失败后调用，误判代价只是多跑一次降级重试。
        val braces = body.count { it == '{' } - body.count { it == '}' }
        val brackets = body.count { it == '[' } - body.count { it == ']' }
        return braces > 0 || brackets > 0
    }

    override fun run(ctx: ActionContext, input: ScanInput): Map<String, Any?> {
        if (keyStore.isEmpty()) {
            log.error("No AI API keys found in database — cannot run AI scan")
            throw ApiError(ErrorCode.AI_UNAVAILABLE, "No AI API keys configured")
        }

        val allModels = listOf(chatClientFactory.defaultModel) + fallbackModels
        val deadlineAtMs = System.currentTimeMillis() + scanDeadlineSec * 1000

        val mediaItems = input.items.map { item ->
            val mimeType = MimeTypeUtils.parseMimeType(item.mediaType)
            Media(mimeType, URI.create(item.imageUrl))
        }

        for (model in allModels) {
            var attemptCount = 0
            /** provider 回源取图失败的次数（单独计数，上界 [MAX_IMAGE_FETCH_ATTEMPTS]）。 */
            var imageFetchFailures = 0
            while (attemptCount < MAX_ATTEMPTS_PER_MODEL) {
                // 总预算检查：剩余时间不足以完成一次完整调用（call-timeout）时不发起新 attempt——
                // 保证所有在途调用都能在预算内结束（最坏总耗时 = deadline，而非 deadline + 一次调用超时）。
                // 网关/客户端超时通常更短，客户端应据此设置自身超时。
                if (System.currentTimeMillis() + chatClientFactory.callTimeoutSec * 1000 >= deadlineAtMs) {
                    log.error("Scan deadline reached (insufficient budget for another call) — giving up. deadline={}s callTimeout={}s scanId={} model={}",
                        scanDeadlineSec, chatClientFactory.callTimeoutSec, input.scanId, model)
                    throw ApiError(ErrorCode.AI_UNAVAILABLE, "AI scan deadline exceeded")
                }

                // 候选全冷却 → null：计为一次 attempt 并 continue（冷却按 key 不分模型，
                // 换模型用的还是同一批 key；游标已推进，下一轮自然换窗口）。
                val doc = keyStore.pick()
                if (doc == null) {
                    attemptCount++
                    continue
                }
                attemptCount++
                val attemptStartMs = System.currentTimeMillis()

                try {
                    val client = chatClientFactory.forKey(doc.key, model)

                    val scanPrompt = scanPromptRegistry.forProject(ctx.projectId)
                    val systemMsg = SystemMessage(scanPrompt.systemPromptFor(input))
                    val userText = scanPrompt.userPrompt(input)
                    val userMsg = UserMessage.builder()
                        .text(userText)
                        .media(*mediaItems.toTypedArray())
                        .build()

                    val prompt = Prompt(listOf(systemMsg, userMsg))
                    log.debug("Scan run start. scanId={} type={} model={} keyId={}",
                        input.scanId, input.type, model, doc.id)

                    val startMs = System.currentTimeMillis()
                    val response = client.prompt(prompt).call()
                    val content = response.content() ?: ""
                    val elapsedMs = System.currentTimeMillis() - startMs
                    // INFO 打耗时（便于统计 AI 延迟），content 只在 DEBUG 打
                    log.info("Scan attempt ok. scanId={} type={} model={} keyId={} duration={}ms contentLength={}",
                        input.scanId, input.type, model, doc.id, elapsedMs, content.length)
                    log.debug("Scan attempt content. scanId={} content={}", input.scanId, content)

                    val data = try {
                        parseJsonToMap(content)
                    } catch (e: Exception) {
                        // 设计 §8.2.1：输出被截断（JSON 不闭合）时**不能整单失败**——
                        // basic_result 可能本来是完整的。识别为截断时不按坏输出换 key 重试整条
                        // （同样的输出规模换 key 大概率还是截断），改用 basic 模板重跑一次：
                        // 产出 basic_result、premium 留空（客户端显示「本次未生成完整报告」）。
                        // BASIC 类型的主模板本来就是 basic 组 → 重跑同模板，语义一致、行为无害。
                        if (input.type == ScanType.BASIC && isLikelyTruncatedJson(content)) {
                            log.warn("AI output likely truncated — one degraded retry with the basic-only template. " +
                                "keyId={}, model={}, rawLength={}", doc.id, model, content.length)
                            val retryPrompt = Prompt(listOf(SystemMessage(scanPrompt.systemPrompt(input)), userMsg))
                            val retryContent = try {
                                client.prompt(retryPrompt).call().content()
                            } catch (retryError: Exception) {
                                log.warn("Degraded basic retry failed. keyId={}, model={} msg={}",
                                    doc.id, model, retryError.message)
                                null
                            }
                            if (retryContent != null) {
                                val retryData = try {
                                    parseJsonToMap(retryContent)
                                } catch (retryParseError: Exception) {
                                    log.warn("Degraded basic retry also unparseable. keyId={} rawLength={}",
                                        doc.id, retryContent.length)
                                    null
                                }
                                if (retryData != null) return retryData
                            }
                            // 降级也失败 → 按原逻辑当普通失败继续换 key
                        }
                        // 解析失败是模型输出问题，不是 key 的问题：不冷却；
                        // attempt 已消耗、游标已推进，下一轮自然换 key。
                        log.warn("AI returned unparseable JSON — skip without cooldown. " +
                                "keyId={}, model={}, rawLength={}", doc.id, model, content.length)
                        continue
                    }

                    return data

                } catch (e: Exception) {
                    // provider 回源取图失败是**瞬态**的（它不是坏图片，也不是 key 的问题——
                    // provider 没能把图从我们的对象存储拉到手就先返回 400）。换一个 key 再试一次
                    // （走不同出口），不少情况下就过了；不冷却 key——它没有错。
                    if (isTransientImageFetchFailure(e)) {
                        imageFetchFailures++
                        if (imageFetchFailures < MAX_IMAGE_FETCH_ATTEMPTS) {
                            log.warn("AI could not fetch image (transient upstream failure) — retrying with next key. " +
                                "imageFetchFailures={} scanId={} model={} keyId={} msg={}",
                                imageFetchFailures, input.scanId, model, doc.id, e.message)
                            continue
                        }
                        log.warn("AI could not fetch image after {} attempts — giving up without cooldown. " +
                            "scanId={} model={} keyId={} msg={}", imageFetchFailures, input.scanId, model, doc.id, e.message)
                        throw ApiError(ErrorCode.INVALID_REQUEST, "AI could not load the image (upstream fetch failed)")
                    }
                    // 400/422：请求数据问题（坏图片等），换 key 重试无意义 → 立即终止，不冷却 key、不重试。
                    if (isNonRetryableRequestError(e)) {
                        log.warn("AI rejected request (non-retryable 400/422) — abort without cooldown/retry. " +
                            "scanId={} model={} keyId={} msg={}", input.scanId, model, doc.id, e.message)
                        throw ApiError(ErrorCode.INVALID_REQUEST, "AI rejected the request (invalid image or input)")
                    }
                    // 分类 → 冷却（冷却在 Redis，跨实例共享）。分类纯函数见 [classify]。
                    val failure = classify(
                        e, cooldownRateLimitedSec, cooldownInvalidKeySec, cooldownTimeoutSec, cooldownOtherSec,
                    )
                    if (failure.typedInvalidKey) {
                        // 类型化 401/403：key 确实失效——长冷却移出轮换 + 永久禁用（PG enabled=false，
                        // 300s 后列表重载彻底移出；DB 写失败在禁用 seam 内收敛，不影响主流程），
                        // 不可逆，需运营手工恢复（enabled=true 或删除行）。
                        // 禁用是 best-effort 副作用，主路径已在 AiConfig.disableKeyFn 内 catch Exception 收敛；
                        // 此处再本地兜一层，保证「任何异常不得导致扫描失败」的不变量（scan/deep-research 共用本 catch 分支）。
                        var disableAttempted = false
                        try {
                            keyStore.disableKey(doc.id)
                            disableAttempted = true
                        } catch (e: Exception) {
                            log.warn("disableKey skipped (best-effort, scan continues). keyId={} model={}", doc.id, model, e)
                        }
                        // 措辞与实际结果一致：DB 写失败（seam 内吞掉）时 key 实际只被冷却，不得断言「已禁用」
                        log.error("AI API key invalid — {} + cooling down. reason={} cooldown={}s keyId={} model={} duration={}ms msg={}",
                            if (disableAttempted) "disabling (PG enabled=false, best-effort)" else "cooldown-only (disable failed)",
                            failure.reason, failure.cooldownSec, doc.id, model, System.currentTimeMillis() - attemptStartMs, e.message)
                    } else if (failure.reason == "401" || failure.reason == "403") {
                        // message 兜底命中：疑似失效但类型化判定未中——只长冷却（1h 自动解冻），不永久禁用（避免误杀好 key）。
                        log.warn("AI API key possibly invalid (message-based, not typed) — cooling down only. reason={} cooldown={}s keyId={} model={} duration={}ms msg={}",
                            failure.reason, failure.cooldownSec, doc.id, model, System.currentTimeMillis() - attemptStartMs, e.message)
                    } else {
                        log.warn("Scan attempt failed — cooling down. reason={} cooldown={}s keyId={} model={} duration={}ms",
                            failure.reason, failure.cooldownSec, doc.id, model, System.currentTimeMillis() - attemptStartMs, e)
                    }
                    keyStore.markCooldown(doc.id, failure.cooldownSec, failure.reason)
                }
            }
            log.warn("All attempts exhausted for model, trying next. model={}", model)
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

        try {
            val map = snakeCaseMapper.readValue(jsonOnly, Map::class.java) as? Map<String, Any?>
                ?: throw ApiError(ErrorCode.AI_UNAVAILABLE, "AI returned non-JSON response")
            return map
        } catch (e: Exception) {
            log.error("JSON parse failed. rawLength={}, cleanedLength={}, jsonOnlyLength={}, first200chars={}",
                jsonText.length, cleaned.length, jsonOnly.length, jsonOnly.take(200))
            throw e
        }
    }
}
