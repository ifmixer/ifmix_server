package com.ifmix.core.api.infra.graphql

import com.ifmix.core.api.entity.common.ActorType
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.auth.RequestParser
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.OperationContext
import com.netflix.graphql.dgs.context.DgsContext
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData
import graphql.schema.GraphQLObjectType
import org.springframework.stereotype.Component
import org.springframework.web.context.request.ServletRequestAttributes

@Component
class OperationContextProvider(private val parser: RequestParser) {

    /**
     * 从 DGS DataFetchingEnvironment 解析请求并构造 OperationContext。
     * 解析 + 校验合一：按 require* 即时校验，失败抛 ApiError（→ GraphQLExceptionHandler → 统一 GraphQL 错误格式）。
     * 无中间 error 状态：token 过期/无效在此处直接抛。
     */
    fun fromDfe(
        dfe: DgsDataFetchingEnvironment,
        requireAppId: Boolean = true,
        /**
         * 主体要求：非 null 表示「必须登录」且限定该 actorType（默认 ACTOR_CUSTOMER = C 端必须是 customer，
         * manager token 进来 403）；null 表示不需要登录（login/refresh/createAnonymous 传 null）。
         * 无论此值如何，只要请求带了 token 就校验其过期/无效。
         */
        requireActorType: ActorType? = AuthJwtService.ACTOR_CUSTOMER,
        requireLocale: Boolean = false,
        requireCountry: Boolean = false,
        requireCurrency: Boolean = false,
    ): OperationContext {
        val requestData = DgsContext.getRequestData(dfe) as? DgsWebMvcRequestData
            ?: throw ApiError(ErrorCode.INTERNAL, "GraphQL context not found")
        val servletRequest = (requestData.webRequest as? ServletRequestAttributes)?.request
            ?: throw ApiError(ErrorCode.INTERNAL, "Native HTTP request not found")

        // 全部解析与校验（含抛错）在 RequestParser 内完成；此处只按 require 调用 + 组装，无抛错/分支逻辑。
        val appId = parser.parseAppId(servletRequest, requireAppId)
        val actor = parser.parseActor(servletRequest, requireActorType)

        val isMutation = dfe.executionStepInfo.parent?.type?.let {
            (it as? GraphQLObjectType)?.name == "Mutation"
        } ?: false

        val ctx = OperationContext(
            appId = appId,
            actorId = actor?.actorId,
            actorType = actor?.actorType,
            anonymous = actor?.anonymous ?: false,
            locale = parser.parseLocale(servletRequest, requireLocale),
            currency = parser.parseCurrency(servletRequest, requireCurrency),
            country = parser.parseCountry(servletRequest, requireCountry),
            clientPlatform = parser.parseClientPlatform(servletRequest),
            clientIp = parser.parseClientIp(servletRequest),
            opName = dfe.field?.name,
            isMutation = isMutation,
            preferReader = !isMutation,
        )
        com.ifmix.core.api.infra.jimmer.OperationContextHolder.set(ctx)
        return ctx
    }
}
