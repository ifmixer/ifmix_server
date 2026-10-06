package com.ifmix.core.api.infra.auth

import com.ifmix.core.api.entity.common.ActorType
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ClientIpResolver
import com.ifmix.core.api.infra.http.ClientPlatform
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.RequestHeaders
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

/** 解析成功的主体（token 校验通过）。 */
data class Actor(
    val actorId: UUID,
    val actorType: ActorType,
    val anonymous: Boolean,
    /** sessionId（token sid claim）。为将来 Redis session 预留，可能为 null（旧 token）。 */
    val sessionId: String? = null,
)

/**
 * 逐字段解析请求信息，结果缓存到 request attribute（同请求多 action 复用）。
 *
 * 校验与抛错**全部在本类内**（parseXxx 自校验自抛，风格统一）；fromDfe 只负责按 require 调用 + 组装。
 * 规则：`if (hasValue || required)` 才校验；带了值就必须合法（否则抛），required 且缺失也抛。
 *
 * 格式软校验（locale/country/currency/各种 version）的严格程度由 [strict] 控制：
 *  - strict=true（测试环境默认）：格式非法 → 抛 ApiError，整个请求报错，尽早暴露客户端 bug。
 *  - strict=false（线上）：格式非法 → 打 WARN log + 当作缺失（null），不影响请求。
 * 通过 `app.header-validation.strict` 配置（默认 true；prod profile 覆盖为 false）。
 * 注意：required 缺失、token、projectId 等硬校验不受此开关影响，任何环境都抛。
 */
@Component
class RequestParser(
    private val jwt: AuthJwtService,
    @param:Value("\${app.header-validation.strict:true}") private val strict: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(RequestParser::class.java)

    /**
     * 格式软校验失败的统一处理：strict 抛错；否则打 WARN 并返回 null（当作未提供）。
     * @param header 头名（用于日志/错误信息）
     * @param raw 原始值（用于日志排查）
     * @param reason 简短原因（如 "invalid format" / "unsupported"）
     */
    private fun onBadFormat(header: String, raw: String, reason: String): Nothing? {
        if (strict) throw ApiError(ErrorCode.INVALID_REQUEST, "invalid $header: $reason")
        log.warn("bad header format ignored. header={} value={} reason={}", header, raw, reason)
        return null
    }


    /** 缺失(required)→抛 required；传了值但格式非法→抛 invalid format；required=false 且未传→null。 */
    fun parseProjectId(request: HttpServletRequest, required: Boolean): String? {
        (request.getAttribute(ATTR_APP_ID) as? String)?.let { return it }
        val raw = request.getHeader(RequestHeaders.PROJECT_ID)
        if (raw.isNullOrBlank()) {
            if (required) throw ApiError(ErrorCode.INVALID_REQUEST, "${RequestHeaders.PROJECT_ID} is required")
            return null
        }
        if (!raw.matches(PROJECT_ID_RE))
            throw ApiError(ErrorCode.INVALID_REQUEST, "invalid ${RequestHeaders.PROJECT_ID} format")
        request.setAttribute(ATTR_APP_ID, raw)
        return raw
    }

    /**
     * 解析并校验主体（actor）——遇到问题**直接抛**（不经中间状态）。
     * - 没带 token：requireActorType!=null（需登录）→ UNAUTHORIZED；否则返回 null。
     * - 带了 token：过期→TOKEN_EXPIRED；验签失败→UNAUTHORIZED（invalid token: signature…）；
     *   跨 app（aud≠x-project-id）→ UNAUTHORIZED（invalid token: app mismatch）；缺 subject→UNAUTHORIZED。
     * - valid：requireActorType 不匹配→FORBIDDEN。
     * requireActorType != null 即「必须登录」。
     */
    fun parseActor(request: HttpServletRequest, requireActorType: ActorType?): Actor? {
        (request.getAttribute(ATTR_ACTOR) as? Actor)?.let { return checkActorType(it, requireActorType) }

        val auth = request.getHeader("Authorization")
        if (auth.isNullOrBlank()) {
            if (requireActorType != null) throw ApiError(ErrorCode.UNAUTHORIZED, "authentication required")
            return null
        }
        if (!auth.startsWith("Bearer "))
            throw ApiError(ErrorCode.UNAUTHORIZED, "invalid token: malformed Authorization header")
        val raw = auth.removePrefix("Bearer ").trim()
        if (raw.isBlank())
            throw ApiError(ErrorCode.UNAUTHORIZED, "invalid token: empty bearer")

        val verified = try {
            jwt.verify(raw)
        } catch (_: TokenExpiredException) {
            throw ApiError(ErrorCode.TOKEN_EXPIRED, "access token expired")
        } ?: throw ApiError(ErrorCode.UNAUTHORIZED, "invalid token: signature verification failed")

        // aud 校验：token 的 projectId(aud) 必须与 header x-project-id 一致，防跨 app 重放 token。
        val headerAppId = request.getHeader(RequestHeaders.PROJECT_ID)?.takeIf { it.isNotBlank() }
        val tokenAppId = verified.projectId
        if (headerAppId != null && tokenAppId != headerAppId)
            throw ApiError(ErrorCode.UNAUTHORIZED, "invalid token: app mismatch")

        // install token 只提供可信 iid，不是 actor，也没有 sub。actor 可选的端点（createAnonymous/updateInstall）
        // 允许继续组装 ActionContext；需要 actor 的业务端点仍明确拒绝。
        if (verified.tokenType == AuthJwtService.TOKEN_TYPE_INSTALL) {
            if (requireActorType != null)
                throw ApiError(ErrorCode.UNAUTHORIZED, "customer authentication required")
            request.setAttribute(ATTR_TOKEN_TYPE, verified.tokenType)
            verified.installId?.let { tryUuid(it) }?.let { request.setAttribute(ATTR_TOKEN_IID, it) }
            return null
        }

        val actorId = verified.actorId?.let { tryUuid(it) }
            ?: throw ApiError(ErrorCode.UNAUTHORIZED, "invalid token: missing or invalid subject")

        val actor = Actor(
            actorId = actorId,
            actorType = verified.actorType,
            anonymous = verified.anonymous,
            sessionId = verified.sessionId,
        )
        request.setAttribute(ATTR_ACTOR, actor)
        return checkActorType(actor, requireActorType)
    }

    private fun checkActorType(actor: Actor, requireActorType: ActorType?): Actor {
        if (requireActorType != null && actor.actorType != requireActorType)
            throw ApiError(ErrorCode.FORBIDDEN, "actor type not allowed for this endpoint")
        return actor
    }

    /** 供日志等只读场景：不抛，仅当 token 有效时返回 actorId（复用 parseActor，吞掉校验异常）。 */
    fun peekActorId(request: HttpServletRequest): UUID? =
        runCatching { parseActor(request, requireActorType = null)?.actorId }.getOrNull()

    /** 传了值则校验（非法格式抛）；未传→null。 */
    fun parseClientPlatform(request: HttpServletRequest): ClientPlatform? {
        val raw = request.getHeader(RequestHeaders.CLIENT_PLATFORM)
        if (raw.isNullOrBlank()) return null
        return try {
            ClientPlatform.fromHeader(raw)
        } catch (_: IllegalArgumentException) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "invalid ${RequestHeaders.CLIENT_PLATFORM}")
        }
    }

    /**
     * x-locale：归一到受支持的语言集（方案 B）。不在支持集内 / 无法识别 → null（不抛错）。
     *
     * 支持集（10 种，BCP 47）：en, zh-CN, zh-TW, ja, fr, es, pt, de, it, nl。
     * 归一规则：按 language subtag 归并（en-US→en、pt-BR→pt…）；中文按 script/region 分简繁
     * （zh / zh-Hans* / zh-SG / zh-MY → zh-CN；zh-TW / zh-HK / zh-MO / zh-Hant* → zh-TW）。
     * 以后加语言只改 [normalizeLocale]。
     */
    fun parseLocale(request: HttpServletRequest, required: Boolean = false): String? {
        val raw = request.getHeader(RequestHeaders.LOCALE)?.takeIf { it.isNotBlank() }
        if (raw == null) {
            if (required) throw ApiError(ErrorCode.INVALID_REQUEST, "${RequestHeaders.LOCALE} is required")
            return null
        }
        // 区分两种「返回 null」：
        //  - 格式非法（无法解析出 language subtag）：软校验，strict 抛 / 线上 WARN。
        //  - 合法 BCP 47 但不在支持集（如 ko、ru）：任何环境都静默返回 null（不是格式 bug）。
        return when (val r = normalizeLocaleResult(raw)) {
            is LocaleResult.Ok -> r.value
            LocaleResult.Malformed -> onBadFormat(RequestHeaders.LOCALE, raw, "invalid format")
            LocaleResult.Unsupported -> if (required) onBadFormat(RequestHeaders.LOCALE, raw, "unsupported") else null
        }
    }

    /** x-currency：ISO 4217 三字母，规范化大写；非法走软校验（strict 抛 / 线上 WARN）。 */
    fun parseCurrency(request: HttpServletRequest, required: Boolean = false): String? =
        parseHeader(request, RequestHeaders.CURRENCY, required) { raw ->
            val up = raw.uppercase()
            if (up.matches(CURRENCY_RE)) up
            else onBadFormat(RequestHeaders.CURRENCY, raw, "invalid format")
        }

    /** x-country：ISO 3166-1 alpha-2 两字母，规范化大写；非法走软校验（strict 抛 / 线上 WARN）。 */
    fun parseCountry(request: HttpServletRequest, required: Boolean = false): String? =
        parseHeader(request, RequestHeaders.COUNTRY, required) { raw ->
            val up = raw.uppercase()
            if (up.matches(COUNTRY_RE)) up
            else onBadFormat(RequestHeaders.COUNTRY, raw, "invalid format")
        }

    /** x-app-version：客户端 App 版本号，原样透传（仅记录用途，不校验格式）。 */
    fun parseAppVersion(request: HttpServletRequest, required: Boolean = false): String? =
        parseHeader(request, RequestHeaders.APP_VERSION, required) { it }

    /** x-ota-version：客户端热更新版本号，形如 `${'$'}{runtimeVersion}-${'$'}{buildNumber}-${'$'}{otaSeq}`（如 `1-23-3`），原样透传（仅记录用途，不校验格式）。 */
    fun parseOtaVersion(request: HttpServletRequest, required: Boolean = false): String? =
        parseHeader(request, RequestHeaders.OTA_VERSION, required) { it }

    fun parseClientIp(request: HttpServletRequest): String = ClientIpResolver.resolve(request)

    /** cf-bot-score：Cloudflare bot score（1-99），仅记录用途；缺失/非法 → null。 */
    fun parseBotScore(request: HttpServletRequest): Int? =
        request.getHeader(RequestHeaders.CF_BOT_SCORE)?.trim()?.toIntOrNull()?.takeIf { it in 1..99 }

    /** x-install-id：客户端安装标识，原样透传（trim，仅记录用途，不校验格式）。缺失返回 null。 */
    fun parseInstallId(request: HttpServletRequest): String? =
        request.getHeader(RequestHeaders.INSTALL_ID)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 从 Authorization token 取可信 installId（iid claim）。无 token / 无 iid / 过期 / 无效 → null（软取，不抛）。
     * install token（type=5）与 customer token（type=10）都可能携带 iid。用于关系维护/updateInstall。
     */
    fun parseTokenInstallId(request: HttpServletRequest): UUID? {
        (request.getAttribute(ATTR_TOKEN_IID) as? UUID)?.let { return it }
        val auth = request.getHeader("Authorization")
        if (auth.isNullOrBlank() || !auth.startsWith("Bearer ")) return null
        val raw = auth.removePrefix("Bearer ").trim()
        if (raw.isBlank()) return null
        val verified = try { jwt.verify(raw) } catch (_: TokenExpiredException) { return null } ?: return null
        request.setAttribute(ATTR_TOKEN_TYPE, verified.tokenType)
        val iid = verified.installId?.let { tryUuid(it) } ?: return null
        request.setAttribute(ATTR_TOKEN_IID, iid)
        return iid
    }

    /**
     * 从 Authorization token 取 type claim（5=install / 10=customer / 20=manager）。
     * 无 token / 过期 / 无效 → null（软取，不抛）。用于区分 bootstrap 凭证类型。
     */
    fun parseTokenType(request: HttpServletRequest): Int? {
        (request.getAttribute(ATTR_TOKEN_TYPE) as? Int)?.let { return it }
        val auth = request.getHeader("Authorization")
        if (auth.isNullOrBlank() || !auth.startsWith("Bearer ")) return null
        val raw = auth.removePrefix("Bearer ").trim()
        if (raw.isBlank()) return null
        val verified = try { jwt.verify(raw) } catch (_: TokenExpiredException) { return null } ?: return null
        request.setAttribute(ATTR_TOKEN_TYPE, verified.tokenType)
        return verified.tokenType
    }

    /** 通用 header：required 且缺失→抛；有值则经 normalize 规范化+校验（非法在 normalize 内抛或按软校验返回 null）。 */
    private fun parseHeader(
        request: HttpServletRequest,
        name: String,
        required: Boolean,
        normalize: (String) -> String?,
    ): String? {
        val raw = request.getHeader(name)?.takeIf { it.isNotBlank() }
        if (raw == null) {
            if (required) throw ApiError(ErrorCode.INVALID_REQUEST, "$name is required")
            return null
        }
        return normalize(raw)
    }

    private fun tryUuid(s: String): UUID? = try { UUID.fromString(s) } catch (_: Exception) { null }

    companion object {
        private const val ATTR_APP_ID = "com.ifmix.parsed.projectId"
        private const val ATTR_ACTOR = "com.ifmix.parsed.actor"
        private const val ATTR_TOKEN_IID = "com.ifmix.parsed.tokenInstallId"
        private const val ATTR_TOKEN_TYPE = "com.ifmix.parsed.tokenType"
        private val CURRENCY_RE = Regex("^[A-Z]{3}$")   // ISO 4217（大写后校验）
        private val COUNTRY_RE = Regex("^[A-Z]{2}$")    // ISO 3166-1 alpha-2（大写后校验）
        /** project slug 主键：小写字母开头，小写字母/数字/连字符，3-30 字符。创建后不可变。 */
        private val PROJECT_ID_RE = Regex("^[a-z][a-z0-9-]{2,29}$")

        /** 非中文的受支持语言：language subtag（小写）→ 规范值。 */
        private val SUPPORTED_LANGS = setOf("en", "ja", "fr", "es", "pt", "de", "it", "nl")

        /** locale 归一结果：区分「格式非法」与「合法但不支持」。 */
        sealed interface LocaleResult {
            data class Ok(val value: String) : LocaleResult
            /** 无法解析出 language subtag（垃圾输入）——格式 bug。 */
            data object Malformed : LocaleResult
            /** 合法 BCP 47 但不在支持集（如 ko、ru）——不是格式 bug。 */
            data object Unsupported : LocaleResult
        }

        /**
         * 归一任意 BCP 47 输入。识别不了区分 [LocaleResult.Malformed]（无 language subtag）
         * 与 [LocaleResult.Unsupported]（有 subtag 但不在支持集）。以后加语言改这里。
         * 支持集：en, zh-CN, zh-TW, ja, fr, es, pt, de, it, nl。
         */
        fun normalizeLocaleResult(raw: String): LocaleResult {
            val locale = try {
                java.util.Locale.forLanguageTag(raw.trim())
            } catch (_: Exception) {
                return LocaleResult.Malformed
            }
            val lang = locale.language.lowercase()
            if (lang.isEmpty()) return LocaleResult.Malformed
            if (lang == "zh") return LocaleResult.Ok(normalizeChinese(locale))
            return if (lang in SUPPORTED_LANGS) LocaleResult.Ok(lang) else LocaleResult.Unsupported
        }

        /**
         * 归一任意 BCP 47 输入到受支持集，识别不了返回 null（不区分 malformed/unsupported）。
         * 保留给不关心细分的调用方。以后加语言改 [normalizeLocaleResult]。
         */
        fun normalizeLocale(raw: String): String? =
            (normalizeLocaleResult(raw) as? LocaleResult.Ok)?.value

        /** 中文按 script/region 分简繁；裸 zh 默认简体。 */
        private fun normalizeChinese(locale: java.util.Locale): String {
            val script = locale.script // Hans / Hant（若 tag 带 script 或可从 region 推断）
            if (script.equals("Hant", ignoreCase = true)) return "zh-TW"
            if (script.equals("Hans", ignoreCase = true)) return "zh-CN"
            return when (locale.country.uppercase()) {
                "TW", "HK", "MO" -> "zh-TW"
                else -> "zh-CN" // CN / SG / MY / 空 → 简体
            }
        }
    }
}
