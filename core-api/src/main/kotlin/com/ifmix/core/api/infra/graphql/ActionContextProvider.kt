package com.ifmix.core.api.infra.graphql

import com.ifmix.core.api.entity.common.ActorType
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.auth.RequestParser
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.ActionContext
import com.netflix.graphql.dgs.context.DgsContext
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData
import graphql.schema.GraphQLObjectType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.context.request.ServletRequestAttributes

@Component
class ActionContextProvider(
    private val parser: RequestParser,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 从 DGS DataFetchingEnvironment 解析请求并构造 ActionContext。
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
    ): ActionContext {
        // 本请求已构建过 → 直接复用（嵌套 resolver / DataLoader 拿到的是顶层构建的原 ctx：
        // isMutation / preferReader / actionName 保持原值）。重建会让嵌套处按 parent type 判出
        // isMutation=false → preferReader=true，mutation 流程内的读误走 reader 池。
        // require* 是 fromDfe 的硬契约：复用时仍逐项校验（对已构建字段做检查，不再解析请求）。
        RequestActionContext.fromDfe(dfe)?.actionContext?.let { cached ->
            checkRequire(cached, requireAppId, requireActorType, requireLocale, requireCountry, requireCurrency)
            log.debug(
                "event=action_context.reuse action={} isMutation={} preferReader={} caller={}",
                cached.actionName, cached.isMutation, cached.preferReader, dfe.field?.name,
            )
            return cached
        }

        val requestData = DgsContext.getRequestData(dfe) as? DgsWebMvcRequestData
            ?: throw ApiError(ErrorCode.INTERNAL, "GraphQL context not found")
        val servletRequest = (requestData.webRequest as? ServletRequestAttributes)?.request
            ?: throw ApiError(ErrorCode.INTERNAL, "Native HTTP request not found")

        // 全部解析与校验（含抛错）在 RequestParser 内完成；此处只按 require 调用 + 组装，无抛错/分支逻辑。
        val projectId = parser.parseProjectId(servletRequest, requireAppId)
        val actor = parser.parseActor(servletRequest, requireActorType)

        val isMutation = dfe.executionStepInfo.parent?.type?.let {
            (it as? GraphQLObjectType)?.name == "Mutation"
        } ?: false

        val ctx = ActionContext(
            projectId = projectId,
            actorId = actor?.actorId,
            actorType = actor?.actorType,
            anonymous = actor?.anonymous ?: false,
            sessionId = actor?.sessionId,
            locale = parser.parseLocale(servletRequest, requireLocale),
            currency = parser.parseCurrency(servletRequest, requireCurrency),
            country = parser.parseCountry(servletRequest, requireCountry),
            clientPlatform = parser.parseClientPlatform(servletRequest),
            clientIp = parser.parseClientIp(servletRequest),
            tokenInstallId = parser.parseTokenInstallId(servletRequest),
            tokenType = parser.parseTokenType(servletRequest),
            botScore = parser.parseBotScore(servletRequest),
            requestId = com.ifmix.core.api.infra.http.LogContext.requestId(servletRequest),
            appVersion = parser.parseAppVersion(servletRequest),
            otaVersion = parser.parseOtaVersion(servletRequest),
            actionName = dfe.field?.name,
            isMutation = isMutation,
            preferReader = !isMutation,
        )
        // 缓存进请求级容器（RequestActionContext，随 DgsContext 跨线程）——这是 ActionContext
        // 唯一的传播通道；不再有 ThreadLocal。
        RequestActionContext.fromDfe(dfe)?.actionContext = ctx
        log.debug(
            "event=action_context.build action={} isMutation={} preferReader={}",
            ctx.actionName, ctx.isMutation, ctx.preferReader,
        )
        com.ifmix.core.api.infra.http.LogContext.bind(ctx, servletRequest)
        return ctx
    }

    /** 复用缓存 ctx 时对 require* 的等价校验（与 RequestParser 首次构建的语义对齐）。 */
    private fun checkRequire(
        ctx: ActionContext,
        requireAppId: Boolean,
        requireActorType: ActorType?,
        requireLocale: Boolean,
        requireCountry: Boolean,
        requireCurrency: Boolean,
    ) {
        if (requireAppId && ctx.projectId == null) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "projectId is required")
        }
        requireActorType?.let {
            if (ctx.actorId == null) throw ApiError(ErrorCode.UNAUTHORIZED, "authentication required")
            if (ctx.actorType != it) throw ApiError(ErrorCode.FORBIDDEN, "actor type not allowed for this endpoint")
        }
        if (requireLocale && ctx.locale == null) throw ApiError(ErrorCode.INVALID_REQUEST, "locale is required")
        if (requireCountry && ctx.country == null) throw ApiError(ErrorCode.INVALID_REQUEST, "country is required")
        if (requireCurrency && ctx.currency == null) throw ApiError(ErrorCode.INVALID_REQUEST, "currency is required")
    }
}
