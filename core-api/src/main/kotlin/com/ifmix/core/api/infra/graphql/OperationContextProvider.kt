package com.ifmix.core.api.infra.graphql

import com.ifmix.core.api.infra.auth.AuthInterceptor
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.OperationContext
import com.ifmix.core.api.infra.http.RequestContext
import com.netflix.graphql.dgs.context.DgsContext
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData
import graphql.schema.GraphQLObjectType
import org.springframework.stereotype.Component
import org.springframework.web.context.request.ServletRequestAttributes

@Component
class OperationContextProvider {

    /**
     * 从 DGS DataFetchingEnvironment 中提取 OperationContext。
     * RequestContext 由 AuthInterceptor 构建并存入 attribute。
     * require* 参数控制必填校验，缺失时抛 ApiError。
     */
    fun fromDfe(
        dfe: DgsDataFetchingEnvironment,
        requireAppId: Boolean = true,
        requireActorId: Boolean = false,
        /**
         * 主体类型护栏：若请求已认证（actorType 非 null），则必须等于此值；否则 403。
         * 默认 ACTOR_CUSTOMER —— C 端 BFF，manager token（20）进来直接 403。
         * 匿名/未认证（actorType==null）不受影响，公开接口照常。
         * 传 null 表示不校验 actorType（将来 manager BFF 传 ACTOR_MANAGER，或按需放开）。
         */
        requireActorType: Int? = AuthJwtService.ACTOR_CUSTOMER,
        requireLocale: Boolean = false,
        requireCountry: Boolean = false,
        requireCurrency: Boolean = false,
    ): OperationContext {
        val requestData = DgsContext.getRequestData(dfe) as? DgsWebMvcRequestData
            ?: throw ApiError(ErrorCode.INTERNAL, "GraphQL context not found")

        val servletRequest = (requestData.webRequest as? ServletRequestAttributes)?.request
            ?: throw ApiError(ErrorCode.INTERNAL, "Native HTTP request not found")

        val reqCtx = servletRequest.getAttribute(AuthInterceptor.ATTR_REQUEST_CONTEXT) as? RequestContext
            ?: throw ApiError(ErrorCode.INTERNAL, "RequestContext not found")

        // require 校验
        if (requireAppId && reqCtx.appId == null) throw ApiError(ErrorCode.INVALID_REQUEST, "x-app-id is required")
        if (requireActorId && reqCtx.actorId == null) throw ApiError(ErrorCode.UNAUTHORIZED, "authentication required")
        if (requireActorType != null && reqCtx.actorType != null && reqCtx.actorType != requireActorType)
            throw ApiError(ErrorCode.FORBIDDEN, "actor type not allowed for this endpoint")
        if (requireLocale && reqCtx.locale == null) throw ApiError(ErrorCode.INVALID_REQUEST, "x-locale is required")
        if (requireCountry && reqCtx.country == null) throw ApiError(ErrorCode.INVALID_REQUEST, "x-country is required")
        if (requireCurrency && reqCtx.currency == null) throw ApiError(ErrorCode.INVALID_REQUEST, "x-currency is required")

        val isMutation = dfe.executionStepInfo.parent?.type?.let {
            (it as? GraphQLObjectType)?.name == "Mutation"
        } ?: false

        val ctx = OperationContext(
            req = reqCtx,
            opName = dfe.field?.name,
            isMutation = isMutation,
            preferReader = !isMutation,
        )
        com.ifmix.core.api.infra.jimmer.OperationContextHolder.set(ctx)
        return ctx
    }
}
