package com.ifmix.core.api.infra.auth

import com.ifmix.core.api.entity.common.ActorType
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ClientIpResolver
import com.ifmix.core.api.infra.http.ClientPlatform
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.RequestHeaders
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import java.util.UUID

/** 解析成功的主体（token 校验通过）。 */
data class Actor(val actorId: UUID, val actorType: ActorType, val anonymous: Boolean)

/**
 * 逐字段解析请求信息，结果缓存到 request attribute（同请求多 operation 复用）。
 *
 * 校验与抛错**全部在本类内**（parseXxx 自校验自抛，风格统一）；fromDfe 只负责按 require 调用 + 组装。
 * 规则：`if (hasValue || required)` 才校验；带了值就必须合法（否则抛），required 且缺失也抛。
 */
@Component
class RequestParser(private val jwt: AuthJwtService) {

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

        val actorId = verified.actorId?.let { tryUuid(it) }
            ?: throw ApiError(ErrorCode.UNAUTHORIZED, "invalid token: missing or invalid subject")

        val actor = Actor(actorId = actorId, actorType = verified.actorType, anonymous = verified.anonymous)
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
        val normalized = normalizeLocale(raw)
        if (normalized == null && required) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "unsupported ${RequestHeaders.LOCALE}")
        }
        return normalized
    }

    /** x-currency：ISO 4217 三字母，规范化大写；非法抛。 */
    fun parseCurrency(request: HttpServletRequest, required: Boolean = false): String? =
        parseHeader(request, RequestHeaders.CURRENCY, required) { raw ->
            raw.uppercase().also {
                if (!it.matches(CURRENCY_RE)) throw ApiError(ErrorCode.INVALID_REQUEST, "invalid ${RequestHeaders.CURRENCY}")
            }
        }

    /** x-country：ISO 3166-1 alpha-2 两字母，规范化大写；非法抛。 */
    fun parseCountry(request: HttpServletRequest, required: Boolean = false): String? =
        parseHeader(request, RequestHeaders.COUNTRY, required) { raw ->
            raw.uppercase().also {
                if (!it.matches(COUNTRY_RE)) throw ApiError(ErrorCode.INVALID_REQUEST, "invalid ${RequestHeaders.COUNTRY}")
            }
        }

    fun parseClientIp(request: HttpServletRequest): String = ClientIpResolver.resolve(request)

    /** x-install-id：客户端安装标识，原样透传（trim，仅记录用途，不校验格式）。缺失返回 null。 */
    fun parseInstallId(request: HttpServletRequest): String? =
        request.getHeader(RequestHeaders.INSTALL_ID)?.trim()?.takeIf { it.isNotEmpty() }

    /** 通用 header：required 且缺失→抛；有值则经 normalize 规范化+校验（非法在 normalize 内抛）。 */
    private fun parseHeader(
        request: HttpServletRequest,
        name: String,
        required: Boolean,
        normalize: (String) -> String,
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
        private val CURRENCY_RE = Regex("^[A-Z]{3}$")   // ISO 4217（大写后校验）
        private val COUNTRY_RE = Regex("^[A-Z]{2}$")    // ISO 3166-1 alpha-2（大写后校验）
        /** project slug 主键：小写字母开头，小写字母/数字/连字符，3-30 字符。创建后不可变。 */
        private val PROJECT_ID_RE = Regex("^[a-z][a-z0-9-]{2,29}$")

        /** 非中文的受支持语言：language subtag（小写）→ 规范值。 */
        private val SUPPORTED_LANGS = setOf("en", "ja", "fr", "es", "pt", "de", "it", "nl")

        /**
         * 归一任意 BCP 47 输入到受支持集，识别不了返回 null。以后加语言改这里。
         * 支持集：en, zh-CN, zh-TW, ja, fr, es, pt, de, it, nl。
         */
        fun normalizeLocale(raw: String): String? {
            val locale = try {
                java.util.Locale.forLanguageTag(raw.trim())
            } catch (_: Exception) {
                return null
            }
            val lang = locale.language.lowercase()
            if (lang.isEmpty()) return null

            if (lang == "zh") return normalizeChinese(locale)
            return if (lang in SUPPORTED_LANGS) lang else null
        }

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
