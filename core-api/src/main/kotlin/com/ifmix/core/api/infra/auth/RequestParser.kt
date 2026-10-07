package com.ifmix.core.api.infra.auth

import com.ifmix.core.api.entity.common.ActorType
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ClientIpResolver
import com.ifmix.core.api.infra.http.ClientPlatform
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.RequestMeta
import com.ifmix.core.api.infra.http.RequestHeaders
import com.ifmix.core.api.infra.http.WireCryptoFilter
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 请求解析的唯一入口，只提供两个方法：
 * - [parseMeta]：请求元信息（[RequestMeta]）。信源：解密 body 的 `meta` 段（加密通道）；
 *   无加密 body 且 wire mode=optional（明文 dev 通道）→ 回落 `x-req-meta` header；required 模式不读 headers。
 *   各字段的格式校验/归一（projectId 正则、locale 归一、currency/country 大写、clientPlatform 枚举）
 *   **在 parseMeta 内一次做完**——调用方拿到 meta 直接取字段，不再有逐字段 parse 方法。
 * - [parseAuthorization]：凭证（裸 token）。信源：解密 body 顶层 `authorization`；无加密 body 且
 *   mode=optional → 回落标准 `Authorization` header；required 模式不读 headers。
 *
 * meta/authorization 的结构违规（非对象、值非字符串、meta 段超限）一律 [ApiError]（400000）。
 * 格式软校验（locale/currency/country）的严格程度由 [strict] 控制：
 *  - strict=true（测试环境默认）：格式非法 → 抛 ApiError，整个请求报错，尽早暴露客户端 bug。
 *  - strict=false（线上）：格式非法 → 打 WARN log + 当作缺失（null），不影响请求。
 * 通过 `app.header-validation.strict` 配置（默认 true；prod profile 覆盖为 false）。
 * 注意：projectId/clientPlatform 等参与路由/分桶的字段是硬校验，不受此开关影响，任何环境都抛。
 *
 * 结果按 request 缓存（同请求多次调用零重复解析）；token 校验（[parseToken]）同样按 request 缓存。
 */
@Component
class RequestParser(
    private val jwt: AuthJwtService,
    private val mapper: ObjectMapper,
    @param:Value("\${app.wire-crypto.mode:required}") private val wireMode: String = "required",
    @param:Value("\${app.header-validation.strict:true}") private val strict: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(RequestParser::class.java)
    private val headerFallbackAllowed = wireMode != "required"

    // ===== meta =====

    /**
     * 本请求的 [RequestMeta]（各字段已归一/校验）。结果缓存在 request 上（[RequestMeta.ATTR_META]）。
     * 未经 filter 的路径/测试（无加密 body、无 header）返回空实例，全部字段为 null。
     */
    fun parseMeta(request: HttpServletRequest): RequestMeta {
        (request.getAttribute(RequestMeta.ATTR_META) as? RequestMeta)?.let { return it }
        val meta = when (val root = payloadRoot(request)) {
            null -> headerMeta(request)
            else -> bodyMeta(root, "body meta")
        } ?: RequestMeta()
        request.setAttribute(RequestMeta.ATTR_META, meta)
        return meta
    }

    /** 解密 body 的 meta 段 → 归一/校验；无 body 或 meta 段缺失 → null（调用方兜底空实例）。 */
    private fun bodyMeta(root: Map<String, Any?>, source: String): RequestMeta? {
        val raw = root[KEY_META] ?: return null
        val map = raw as? Map<*, *> ?: throw ApiError(ErrorCode.INVALID_REQUEST, "invalid $source: must be an object")
        val size = map.entries.sumOf { it.key.toString().length + it.value.toString().length }
        if (size > MAX_META_BYTES) throw ApiError(ErrorCode.INVALID_REQUEST, "invalid $source: too large")
        val plain = linkedMapOf<String, String?>()
        for ((k, v) in map) {
            if (v == null) continue
            plain[k.toString()] = v as? String
                ?: throw ApiError(ErrorCode.INVALID_REQUEST, "invalid $source: value must be a string")
        }
        return normalize(RequestMeta(
            reqId = plain["reqId"],
            projectId = plain["projectId"],
            locale = plain["locale"],
            currency = plain["currency"],
            country = plain["country"],
            appVersion = plain["appVersion"],
            otaVersion = plain["otaVersion"],
            clientPlatform = plain["clientPlatform"],
        ), source)
    }

    /** 明文 dev 通道：`x-req-meta` header（仅 optional 模式）。缺失 → null；非法 JSON/结构 → 400000。 */
    private fun headerMeta(request: HttpServletRequest): RequestMeta? {
        if (!headerFallbackAllowed) return null
        val raw = request.getHeader(RequestHeaders.REQ_META)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val meta = try {
            mapper.readValue(raw, RequestMeta::class.java)
        } catch (e: JacksonException) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "invalid ${RequestHeaders.REQ_META}: ${e.message}")
        }
        return normalize(meta, RequestHeaders.REQ_META)
    }

    /** 逐字段归一/校验（parseMeta 一次做完，调用方直接取字段）。 */
    private fun normalize(meta: RequestMeta, source: String): RequestMeta = RequestMeta(
        reqId = meta.reqId?.sanitizeReqId(),
        projectId = meta.projectId?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (!it.matches(PROJECT_ID_RE)) throw ApiError(ErrorCode.INVALID_REQUEST, "invalid projectId format")
        },
        locale = meta.locale?.trim()?.takeIf { it.isNotEmpty() }?.let { normalizeLocaleField(it, source) },
        currency = meta.currency?.trim()?.takeIf { it.isNotEmpty() }?.let { normalizeCodeField(it, "currency", CURRENCY_RE, source) },
        country = meta.country?.trim()?.takeIf { it.isNotEmpty() }?.let { normalizeCodeField(it, "country", COUNTRY_RE, source) },
        appVersion = meta.appVersion?.trim()?.takeIf { it.isNotEmpty() },
        otaVersion = meta.otaVersion?.trim()?.takeIf { it.isNotEmpty() },
        clientPlatform = meta.clientPlatform?.trim()?.takeIf { it.isNotEmpty() }?.also {
            // 平台参与限流分桶，硬校验（不做软校验）
            runCatching { ClientPlatform.fromHeader(it) }
                .onFailure { throw ApiError(ErrorCode.INVALID_REQUEST, "invalid clientPlatform") }
        },
    )

    /** locale：全语言支持——归一集内的语言做归并/分简繁，其余合法 BCP 47 原样透传；解析不出 language subtag 才走软校验。 */
    private fun normalizeLocaleField(raw: String, source: String): String? =
        normalizeLocale(raw) ?: onBadFormat("locale", raw, "invalid format ($source)")

    /** ISO 码（currency 3 字母 / country 2 字母）规范化大写；非法走软校验。 */
    private fun normalizeCodeField(raw: String, name: String, re: Regex, source: String): String? {
        val up = raw.uppercase()
        return if (up.matches(re)) up else onBadFormat(name, raw, "invalid format ($source)")
    }

    /** 格式软校验失败的统一处理：strict 抛错；否则打 WARN 并返回 null（当作未提供）。 */
    private fun onBadFormat(field: String, raw: String, reason: String): Nothing? {
        if (strict) throw ApiError(ErrorCode.INVALID_REQUEST, "invalid $field: $reason")
        log.warn("bad meta field ignored. field={} value={} reason={}", field, raw, reason)
        return null
    }

    // ===== authorization =====

    /**
     * 凭证（裸 token，已剥 `Bearer ` 前缀；大小写不敏感；纯 scheme / 空白视为未提供 → null）。
     * 加密 body 顶层 `authorization` 优先；无加密 body 且 optional 模式 → 回落 `Authorization` header。
     */
    fun parseAuthorization(request: HttpServletRequest): String? {
        payloadRoot(request)?.let { root ->
            return when (val auth = root[KEY_AUTHORIZATION]) {
                null -> null
                is String -> stripBearer(auth)
                else -> throw ApiError(ErrorCode.INVALID_REQUEST, "invalid authorization: must be a string")
            }
        }
        if (!headerFallbackAllowed) return null
        return request.getHeader(HttpHeaders.AUTHORIZATION)?.let(::stripBearer)
    }

    /** 解密 body 缓存（[WireCryptoFilter.ATTR_DECRYPTED_BODY]）→ JSON Map，结果缓存复用；无加密 body → null。 */
    @Suppress("UNCHECKED_CAST")
    private fun payloadRoot(request: HttpServletRequest): Map<String, Any?>? {
        (request.getAttribute(ATTR_ROOT) as? Map<String, Any?>)?.let { return it }
        val body = request.getAttribute(WireCryptoFilter.ATTR_DECRYPTED_BODY) as? ByteArray ?: return null
        val root: Map<String, Any?> = try {
            mapper.readValue(body, Map::class.java) as Map<String, Any?>
        } catch (_: JacksonException) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "invalid decrypted payload: not a JSON object")
        }
        request.setAttribute(ATTR_ROOT, root)
        return root
    }

    /** 剥 Bearer scheme：`Bearer xxx` / `bearer xxx` → `xxx`；裸 token 原样；纯 scheme / 空白 → null。 */
    private fun stripBearer(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.equals("Bearer", ignoreCase = true)) return null
        if (t.startsWith("Bearer ", ignoreCase = true)) return t.substring(7).trim().takeIf { it.isNotEmpty() }
        return t
    }

    // ===== token（信源 = parseAuthorization）=====

    /**
     * 解析并校验 token——遇到问题**直接抛**（不经中间状态）。直接返回 JWT 的 [VerifiedToken]，不做二次包装：
     * - 没带 token：requireActorType!=null（需登录）→ UNAUTHORIZED；否则返回 null。
     * - 带了 token：过期→TOKEN_EXPIRED；验签失败或 claim 非法（缺失/类型不符）→UNAUTHORIZED（invalid token）；
     *   跨 app（aud≠meta.projectId）→ UNAUTHORIZED（invalid token: app mismatch）；
     *   非 install token 缺 subject→UNAUTHORIZED。
     * - requireActorType != null：install token（无 sub，只带 iid）→ UNAUTHORIZED（customer authentication
     *   required）；actorType 不符 → FORBIDDEN。
     * install token 在 requireActorType==null 时也返回（sub=null，iid/type 可用）。结果按 request 缓存。
     */
    fun parseToken(request: HttpServletRequest, requireActorType: ActorType?): VerifiedToken? {
        (request.getAttribute(ATTR_TOKEN) as? VerifiedToken)?.let { return checkToken(it, requireActorType) }

        val token = parseAuthorization(request)
        if (token == null) {
            if (requireActorType != null) throw ApiError(ErrorCode.UNAUTHORIZED, "authentication required")
            return null
        }

        val verified = try {
            jwt.verify(token)
        } catch (_: TokenExpiredException) {
            throw ApiError(ErrorCode.TOKEN_EXPIRED, "access token expired")
        } ?: throw ApiError(ErrorCode.UNAUTHORIZED, "invalid token")

        // aud 校验：token 的 projectId(aud) 必须与 meta.projectId 一致，防跨 app 重放 token。
        val metaAppId = parseMeta(request).projectId
        val tokenAppId = verified.projectId
        if (metaAppId != null && tokenAppId != metaAppId)
            throw ApiError(ErrorCode.UNAUTHORIZED, "invalid token: app mismatch")

        // install token 只提供可信 iid（无 sub）；customer/manager token 必须有 sub。
        if (verified.tokenType != AuthJwtService.TOKEN_TYPE_INSTALL && verified.actorId == null)
            throw ApiError(ErrorCode.UNAUTHORIZED, "invalid token: missing or invalid subject")

        request.setAttribute(ATTR_TOKEN, verified)
        return checkToken(verified, requireActorType)
    }

    private fun checkToken(token: VerifiedToken, requireActorType: ActorType?): VerifiedToken {
        if(token.tokenType == AuthJwtService.TOKEN_TYPE_INSTALL || token.tokenType == AuthJwtService.TOKEN_TYPE_CUSTOMER){
            if(token.installId == null){
                throw ApiError(ErrorCode.UNAUTHORIZED, "invalid token, require install")
            }
        }
        if (requireActorType == null) return token
        if (token.tokenType == AuthJwtService.TOKEN_TYPE_INSTALL)
            throw ApiError(ErrorCode.UNAUTHORIZED, "customer authentication required")
        // access token 的 type claim 即 actorType 编码（10/20），直接与 requireActorType 比较
        if (token.tokenType != requireActorType)
            throw ApiError(ErrorCode.FORBIDDEN, "actor type not allowed for this endpoint")
        return token
    }

    /**
     * 已组装 ctx 的 require* 硬校验（语义与 [parseToken] 一致，供复用缓存 ctx 的路径复检）：
     * 需要登录但没有主体 → UNAUTHORIZED；类型不符 → FORBIDDEN。
     */
    fun checkActorRequirement(actorId: UUID?, actorType: ActorType?, requireActorType: ActorType?) {
        requireActorType?.let {
            if (actorId == null) throw ApiError(ErrorCode.UNAUTHORIZED, "authentication required")
            if (actorType != it) throw ApiError(ErrorCode.FORBIDDEN, "actor type not allowed for this endpoint")
        }
    }

    /** 供日志等只读场景：不抛，仅当 token 有效时返回 actorId（复用 parseToken，吞掉校验异常）。 */
    fun peekActorId(request: HttpServletRequest): UUID? =
        runCatching { parseToken(request, requireActorType = null)?.actorId?.let(::tryUuid) }.getOrNull()

    // ===== 非 meta 的真实 header =====

    fun parseClientIp(request: HttpServletRequest): String = ClientIpResolver.resolve(request)

    /** cf-bot-score：Cloudflare bot score（1-99），仅记录用途；缺失/非法 → null。 */
    fun parseBotScore(request: HttpServletRequest): Int? =
        request.getHeader(RequestHeaders.CF_BOT_SCORE)?.trim()?.toIntOrNull()?.takeIf { it in 1..99 }

    /** reqId 清洗：去控制字符（防伪造日志行注入）、去首尾空白、限长。 */
    private fun String.sanitizeReqId(): String? =
        filterNot { it.isISOControl() }.trim().take(MAX_REQ_ID_LEN).takeIf { it.isNotEmpty() }

    private fun tryUuid(s: String): UUID? = try { UUID.fromString(s) } catch (_: Exception) { null }

    companion object {
        private const val ATTR_ROOT = "com.ifmix.parsed.payloadRoot"
        private const val ATTR_TOKEN = "com.ifmix.parsed.verifiedToken"
        private const val KEY_META = "meta"
        private const val KEY_AUTHORIZATION = "authorization"
        private const val MAX_REQ_ID_LEN = 128
        /** meta 段（key+值）总字符数上限；超限 400000 大声拒绝（量级对齐 Tomcat 8KB 请求头区）。 */
        private const val MAX_META_BYTES = 8 * 1024
        private val CURRENCY_RE = Regex("^[A-Z]{3}$")   // ISO 4217（大写后校验）
        private val COUNTRY_RE = Regex("^[A-Z]{2}$")    // ISO 3166-1 alpha-2（大写后校验）
        /** project slug 主键：小写字母开头，小写字母/数字/连字符，3-30 字符。创建后不可变。 */
        private val PROJECT_ID_RE = Regex("^[a-z][a-z0-9-]{2,29}$")

        /** 归一集：这些语言的 region/script 变体按 language subtag 归并（en-US→en、pt-BR→pt）。其余语言原样透传。 */
        private val COLLAPSED_LANGS = setOf("en", "ja", "fr", "es", "pt", "de", "it", "nl")

        /**
         * locale 归一（全语言支持，只归一部分）：
         * - `zh*` 按 script/region 分简繁（zh-HK→zh-TW、zh-SG→zh-CN，裸 zh 默认简体）；
         * - [COLLAPSED_LANGS] 内的语言按 language subtag 归并（en-US→en）；
         * - 其余合法 BCP 47（ko、ru-RU、th…）**原样透传**——服务端不认识的值不动；
         * - 解析不出 language subtag（垃圾输入）→ null（软校验：strict 抛 / 线上 WARN+null）。
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
            return if (lang in COLLAPSED_LANGS) lang else raw.trim()
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

/** Jackson 解析失败（JSON 结构层），由调用方转成对应的 ApiError。 */
private typealias JacksonException = tools.jackson.core.JacksonException
