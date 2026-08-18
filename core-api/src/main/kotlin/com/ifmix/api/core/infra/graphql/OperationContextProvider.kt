package com.ifmix.api.core.infra.graphql

import com.ifmix.api.core.infra.auth.AuthInterceptor
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ClientIpResolver
import com.ifmix.api.core.infra.http.ClientPlatform
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.netflix.graphql.dgs.context.DgsContext
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData
import graphql.schema.GraphQLObjectType
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import org.springframework.web.context.request.ServletRequestAttributes

@Component
class OperationContextProvider {

    /**
     * 从 DGS DataFetchingEnvironment 中提取 OperationContext。
     * 自动判断 query/mutation，构建 repoCtx（含 DSLContext）。
     */
    fun fromDfe(dfe: DgsDataFetchingEnvironment): OperationContext {
        val requestData = DgsContext.getRequestData(dfe) as? DgsWebMvcRequestData
            ?: throw ApiError(ErrorCode.INTERNAL, "GraphQL context not found")

        val servletRequest = (requestData.webRequest as? ServletRequestAttributes)?.request
            ?: throw ApiError(ErrorCode.INTERNAL, "Native HTTP request not found")

        val userIdStr = servletRequest.getAttribute(AuthInterceptor.ATTR_USER_ID) as? String

        val isMutation = dfe.executionStepInfo.parent?.type?.let {
            (it as? GraphQLObjectType)?.name == "Mutation"
        } ?: false
        val opName = dfe.field?.name

        // 将来多集群：根据 appId 从 DataSourceRegistry 解析不同的 DSLContext
        val repoCtx = RepoContext.DEFAULT

        return OperationContext(
            appId = parseUuid(servletRequest.getHeader("x-app-id")),
            installId = parseUuid(servletRequest.getHeader("x-install-id")),
            lang = servletRequest.getHeader("x-lang").takeIf { !it.isNullOrBlank() },
            currency = servletRequest.getHeader("x-currency").takeIf { !it.isNullOrBlank() },
            country = servletRequest.getHeader("x-country").takeIf { !it.isNullOrBlank() },
            clientPlatform = ClientPlatform.fromHeader(servletRequest.getHeader("x-client-platform")),
            userId = userIdStr?.let { tryParseUuid(it) },
            clientIp = ClientIpResolver.resolve(servletRequest),
            readFromReplica = !isMutation,
            opName = opName,
            isMutation = isMutation,
            repoCtx = repoCtx,
        )
    }

    private fun parseUuid(header: String?): java.util.UUID? =
        header?.takeIf { it.isNotBlank() }?.let { tryParseUuid(it) }

    private fun tryParseUuid(s: String): java.util.UUID? =
        runCatching { java.util.UUID.fromString(s) }.getOrNull()
}
