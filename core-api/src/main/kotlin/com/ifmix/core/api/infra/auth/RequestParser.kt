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

/** token 解析状态。 */
enum class TokenStatus { ABSENT, VALID, EXPIRED, INVALID }

/** token 解析结果（单类 + status 字段，不用 sealed 多态）。 */
data class ParsedToken(
    val status: TokenStatus,
    val actorId: UUID? = null,
    val actorType: ActorType? = null,
    val anonymous: Boolean = false,
)

/**
 * 逐字段解析请求信息，结果缓存到 request attribute（同请求多 operation 复用）。
 *
 * 校验与抛错**全部在本类内**（parseXxx 自校验自抛，风格统一）；fromDfe 只负责按 require 调用 + 组装，不再有抛错逻辑。
 * 规则：`if (hasValue || required)` 才校验；带了值就必须合法（否则抛），required 且缺失也抛。
 */
@Component
class RequestParser(private val jwt: AuthJwtService) {

    /** 缺失(required)→抛 required；传了值但格式非法→抛 invalid format；required=false 且未传→null。 */
    fun parseAppId(request: HttpServletRequest, required: Boolean): UUID? {
        (request.getAttribute(ATTR_APP_ID) as? UUID)?.let { return it }
        val raw = request.getHeader(RequestHeaders.APP_ID)
        if (raw.isNullOrBlank()) {
            if (required) throw ApiError(ErrorCode.INVALID_REQUEST, "${RequestHeaders.APP_ID} is required")
            return null
        }
        val uuid = tryUuid(raw) ?: throw ApiError(ErrorCode.INVALID_REQUEST, "invalid ${RequestHeaders.APP_ID} format")
        request.setAttribute(ATTR_APP_ID, uuid)
        return uuid
    }

    /**
     * 解析并校验主体（actor）。所有抛错在此：
     * - 带了 token：过期→TOKEN_EXPIRED / 无效→UNAUTHORIZED（无论是否 require）。
     * - 没带 token：requireActorType!=null（需登录）→UNAUTHORIZED；否则返回 null。
     * - valid：requireActorType 不匹配→FORBIDDEN；需登录且 actorId 空→UNAUTHORIZED。
     * requireActorType != null 即表示「必须登录」（并限定该类型）；null 表示不需要登录。
     */
    fun parseActor(request: HttpServletRequest, requireActorType: ActorType?): ParsedToken? {
        val requireLogin = requireActorType != null
        val token = parseToken(request)
        when (token.status) {
            TokenStatus.EXPIRED -> throw ApiError(ErrorCode.TOKEN_EXPIRED, "access token expired")
            TokenStatus.INVALID -> throw ApiError(ErrorCode.UNAUTHORIZED, "invalid token")
            TokenStatus.ABSENT ->
                if (requireLogin) throw ApiError(ErrorCode.UNAUTHORIZED, "authentication required") else return null
            TokenStatus.VALID -> {
                if (requireActorType != null && token.actorType != requireActorType)
                    throw ApiError(ErrorCode.FORBIDDEN, "actor type not allowed for this endpoint")
                if (requireLogin && token.actorId == null)
                    throw ApiError(ErrorCode.UNAUTHORIZED, "authentication required")
                return token
            }
        }
    }

    /** 供日志等只读场景：不抛，仅当 VALID 时返回 actorId。 */
    fun peekActorId(request: HttpServletRequest): UUID? =
        parseToken(request).takeIf { it.status == TokenStatus.VALID }?.actorId

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

    /** x-locale：规范化为 BCP 47（语言小写、地区大写，如 zh-cn→zh-CN）；非法抛。 */
    fun parseLocale(request: HttpServletRequest, required: Boolean = false): String? =
        parseHeader(request, RequestHeaders.LOCALE, required) { raw ->
            if (!raw.matches(LOCALE_RE)) throw ApiError(ErrorCode.INVALID_REQUEST, "invalid ${RequestHeaders.LOCALE}")
            java.util.Locale.forLanguageTag(raw).toLanguageTag().also {
                if (it == "und") throw ApiError(ErrorCode.INVALID_REQUEST, "invalid ${RequestHeaders.LOCALE}")
            }
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

    /**
     * 通用 header 解析：required 且缺失 → 抛；有值则经 [normalize] 规范化+校验（非法在 normalize 内抛）。
     * 遵循「hasValue || required 才处理」：没传且不 required → null。
     */
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

    /** 纯解析 token（不抛）→ ParsedToken，缓存到 request attribute。 */
    private fun parseToken(request: HttpServletRequest): ParsedToken {
        (request.getAttribute(ATTR_TOKEN) as? ParsedToken)?.let { return it }
        val result = doParseToken(request)
        request.setAttribute(ATTR_TOKEN, result)
        return result
    }

    private fun doParseToken(request: HttpServletRequest): ParsedToken {
        val auth = request.getHeader("Authorization") ?: return ParsedToken(TokenStatus.ABSENT)
        if (!auth.startsWith("Bearer ")) return ParsedToken(TokenStatus.INVALID)
        val raw = auth.removePrefix("Bearer ").trim()
        if (raw.isBlank()) return ParsedToken(TokenStatus.INVALID)

        val verified = try {
            jwt.verify(raw)
        } catch (_: TokenExpiredException) {
            return ParsedToken(TokenStatus.EXPIRED)
        } ?: return ParsedToken(TokenStatus.INVALID)

        // aud 校验：token 的 appId(aud) 必须与 header x-app-id 一致，防跨 app 重放 token（单签名密钥下的 app 隔离防线）。
        // header 无 x-app-id 或格式非法时不在此判定（交 parseAppId 处理必填/格式）。
        val headerAppId = request.getHeader(RequestHeaders.APP_ID)?.let { tryUuid(it) }
        if (headerAppId != null && verified.appId != headerAppId.toString()) return ParsedToken(TokenStatus.INVALID)
        val actorId = verified.actorId?.let { tryUuid(it) ?: return ParsedToken(TokenStatus.INVALID) }
        return ParsedToken(TokenStatus.VALID, actorId = actorId, actorType = verified.actorType, anonymous = verified.anonymous)
    }

    private fun tryUuid(s: String): UUID? = try { UUID.fromString(s) } catch (_: Exception) { null }

    companion object {
        private const val ATTR_APP_ID = "com.ifmix.parsed.appId"
        private const val ATTR_TOKEN = "com.ifmix.parsed.token"
        /** BCP 47 粗校验：语言 2-3 字母 + 可选 -地区(2 字母)或 -UN M.49(3 数字)。 */
        private val LOCALE_RE = Regex("^[A-Za-z]{2,3}(-[A-Za-z]{2}|-[0-9]{3})?$")
        private val CURRENCY_RE = Regex("^[A-Z]{3}$")   // ISO 4217（大写后校验）
        private val COUNTRY_RE = Regex("^[A-Z]{2}$")    // ISO 3166-1 alpha-2（大写后校验）
    }
}
