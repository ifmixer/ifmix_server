package com.ifmix.core.api.infra.auth

import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ClientIpResolver
import com.ifmix.core.api.infra.http.ClientPlatform
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.RequestContext
import com.ifmix.core.api.infra.http.RequestHeaders
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import java.util.UUID

/**
 * 请求解析拦截器。从 headers + Bearer token 中构建 [RequestContext] 存入 request attribute。
 * - header 未传：对应字段为 null（由下游 require* 校验是否必填）
 * - header 传了但格式非法：直接报错 400/401
 */
@Component
class AuthInterceptor(private val jwt: AuthJwtService) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val headerAppId = parseAppId(request)
        val token = parseToken(request, headerAppId)

        val reqCtx = RequestContext(
            appId = headerAppId ?: token?.appId,
            actorId = token?.actorId,
            actorType = token?.actorType,
            anonymous = token?.anonymous ?: false,
            locale = request.getHeader(RequestHeaders.LOCALE)?.takeIf { it.isNotBlank() },
            currency = request.getHeader(RequestHeaders.CURRENCY)?.takeIf { it.isNotBlank() },
            country = request.getHeader(RequestHeaders.COUNTRY)?.takeIf { it.isNotBlank() },
            clientPlatform = parseClientPlatform(request),
            clientIp = ClientIpResolver.resolve(request),
        )
        request.setAttribute(ATTR_REQUEST_CONTEXT, reqCtx)
        return true
    }

    private fun parseAppId(request: HttpServletRequest): UUID? {
        val raw = request.getHeader(RequestHeaders.APP_ID) ?: return null
        return tryParseUuid(raw)
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-app-id is not a valid UUID")
    }

    private fun parseToken(request: HttpServletRequest, headerAppId: UUID?): ParsedToken? {
        val auth = request.getHeader("Authorization") ?: return null
        if (!auth.startsWith("Bearer "))
            throw ApiError(ErrorCode.INVALID_REQUEST, "Authorization header must start with 'Bearer '")
        val raw = auth.removePrefix("Bearer ").trim()
        if (raw.isBlank())
            throw ApiError(ErrorCode.INVALID_REQUEST, "Bearer token is empty")

        val verified = jwt.verify(raw)
            ?: throw ApiError(ErrorCode.UNAUTHORIZED, "invalid or expired token")

        // token.appId 必须与 header x-app-id 一致（若 header 存在）
        if (headerAppId != null && verified.appId != headerAppId.toString())
            throw ApiError(ErrorCode.UNAUTHORIZED, "token appId does not match x-app-id header")

        val appId = verified.appId?.let {
            tryParseUuid(it) ?: throw ApiError(ErrorCode.UNAUTHORIZED, "token contains invalid appId")
        }
        val actorId = verified.actorId?.let {
            tryParseUuid(it) ?: throw ApiError(ErrorCode.UNAUTHORIZED, "token contains invalid actorId")
        }
        return ParsedToken(appId = appId, actorId = actorId, actorType = verified.actorType, anonymous = verified.anonymous)
    }

    private fun parseClientPlatform(request: HttpServletRequest): ClientPlatform? {
        val raw = request.getHeader(RequestHeaders.CLIENT_PLATFORM) ?: return null
        return ClientPlatform.fromHeader(raw)
    }

    private fun tryParseUuid(s: String): UUID? = try { UUID.fromString(s) } catch (_: Exception) { null }

    companion object {
        const val ATTR_REQUEST_CONTEXT = "com.ifmix.req.context"
    }
}

private data class ParsedToken(val appId: UUID?, val actorId: UUID?, val actorType: com.ifmix.core.api.entity.common.ActorType?, val anonymous: Boolean)
