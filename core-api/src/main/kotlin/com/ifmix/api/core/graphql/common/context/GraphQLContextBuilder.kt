package com.ifmix.api.core.graphql.common.context

import com.ifmix.api.core.common.http.ActorType
import com.ifmix.api.core.common.http.Bff
import com.ifmix.api.core.common.http.ClientPlatform
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.http.RequestHeaders
import com.netflix.graphql.dgs.context.DgsCustomContextBuilder
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

/**
 * GraphQL 请求上下文构建器。
 * 从 HTTP headers 构造 RequestContext（含 bff / permissions / actorType），存入 DGS custom context。
 */
@Component
class DgsCustomContextBuilderImpl(
    private val request: HttpServletRequest,
) : DgsCustomContextBuilder<RequestContext> {

    override fun build(): RequestContext {
        val appId = request.getHeader(RequestHeaders.APP_ID) ?: ""
        val installId = request.getHeader(RequestHeaders.INSTALL_ID)
        val role = request.getHeader("X-Role") ?: "customer"
        val userId = request.getHeader("X-User-Id") // POC mock: 直接从 header 取

        val permissions = ROLE_PERMISSIONS[role] ?: emptySet()

        // 从请求 URI 判断 BFF 类型
        val bff = if (request.requestURI.startsWith("/admin/")) Bff.ADMIN else Bff.CUSTOMER

        val actorType = when {
            bff == Bff.ADMIN -> ActorType.ADMIN
            userId != null -> ActorType.CUSTOMER_USER
            installId != null -> ActorType.CUSTOMER_INSTALL
            else -> ActorType.CUSTOMER_INSTALL
        }

        return RequestContext(
            appId = appId,
            operationId = request.getHeader("x-op-id")
                ?: (request.getAttribute("trusted.operation.name") as? String),
            installId = installId,
            lang = request.getHeader(RequestHeaders.LANG),
            currency = request.getHeader(RequestHeaders.CURRENCY),
            country = request.getHeader(RequestHeaders.COUNTRY),
            clientPlatform = ClientPlatform.fromHeader(request.getHeader(RequestHeaders.CLIENT_PLATFORM)),
            userId = userId,
            bff = bff,
            permissions = permissions,
            actorType = actorType,
        )
    }

    companion object {
        /** POC mock：通过 X-Role header 判定角色 → permissions 集合。 */
        val ROLE_PERMISSIONS = mapOf(
            "customer" to setOf(
                "todo:read", "todo:write",
                "scan:read", "scan:write",
                "storage:read", "storage:write",
                "collection:read", "collection:write",
                "feedback:write",
                "iap:write",
            ),
            "admin" to setOf(
                "todo:read", "todo:write", "todo:batch", "todo:admin",
                "scan:read", "scan:write",
                "storage:read", "storage:write",
                "collection:read", "collection:write",
                "feedback:write",
                "iap:write",
                "appconfig:write",
            ),
        )
    }
}
