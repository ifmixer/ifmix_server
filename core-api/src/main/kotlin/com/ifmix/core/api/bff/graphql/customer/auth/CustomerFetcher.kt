package com.ifmix.core.api.bff.graphql.customer.auth

import com.ifmix.core.api.entity.auth.customer.Customer
import com.ifmix.core.api.generated.types.CreateAnonymousResult
import com.ifmix.core.api.infra.graphql.ActionContextProvider
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.jimmer.ActionContextHolder
import com.ifmix.core.api.infra.ratelimit.RateLimitProperties
import com.ifmix.core.api.infra.ratelimit.RateLimiter
import com.ifmix.core.api.infra.ratelimit.RateLimitResult
import com.ifmix.core.api.infra.ratelimit.Window
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.auth.AuthFacade
import com.ifmix.core.api.modules.auth.customer.CustomerFacade
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsData
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation

@DgsComponent
class CustomerFetcher(
    private val authService: AuthFacade,
    private val customerFacade: CustomerFacade,
    private val globalTx: GlobalTxRunner,
    private val ctxProvider: ActionContextProvider,
    private val rateLimiter: RateLimiter,
    private val rlProps: RateLimitProperties,
) {

    @DgsMutation(field = "m_auth_customer_createAnonymous")
    fun createAnonymousCustomer(dfe: DgsDataFetchingEnvironment): CreateAnonymousResult {
        // 建匿名号是获取首个凭证的入口，无需登录：requireActorType=null。
        val ctx = ctxProvider.fromDfe(dfe, requireActorType = null)
        // 只要求携带有效可信 iid（token 类型不限）：install token 是首装主路径，
        // 但含 iid 的 customer token 等也可 bootstrap；不再强制 type=5。iid 无效/缺失 → UNAUTHORIZED（进入事务前）。
        // v1.0.6 起 legacy fallback（x-install-id header 回退）已删除，无 token iid 的请求在此即 401000。
        ctx.mustGetTokenInstallId()
        // 下游 install 层限流（attest 规格 §4.6「下游接口的 install 层」）：
        // install 层（5/install/UTC 天）→ IP 层（100/60s + 1000/天）；
        // 执行顺序 install 层 → IP 层 → 业务；install 层拒绝不碰 IP 计数器；IP 层拒绝 install 额度不退。
        val projectId = ctx.mustGetProjectId()
        val clientIp = ctx.clientIp ?: "unknown"
        val iid = ctx.mustGetTokenInstallId()
        when (val rl = rateLimiter.check(
            Window.UTC_DAY,
            "ratelimit:$projectId:anonymous:install:day:$iid",
            rlProps.anonymous.installDay,
        )) {
            RateLimitResult.Allowed, RateLimitResult.Degraded -> Unit
            is RateLimitResult.Limited ->
                throw ApiError(ErrorCode.RATE_LIMITED, "too many anonymous customer creations", retryAfterSec = rl.retryAfterSec)
        }
        when (val rl = rateLimiter.check(
            Window.MINUTE,
            "ratelimit:$projectId:anonymous:ip:min:$clientIp",
            rlProps.anonymous.ipMinute,
        )) {
            RateLimitResult.Allowed, RateLimitResult.Degraded -> Unit
            is RateLimitResult.Limited ->
                throw ApiError(ErrorCode.RATE_LIMITED, "too many anonymous customer creations", retryAfterSec = rl.retryAfterSec)
        }
        when (val rl = rateLimiter.check(
            Window.UTC_DAY,
            "ratelimit:$projectId:anonymous:ip:day:$clientIp",
            rlProps.anonymous.ipDay,
        )) {
            RateLimitResult.Allowed, RateLimitResult.Degraded -> Unit
            is RateLimitResult.Limited ->
                throw ApiError(ErrorCode.RATE_LIMITED, "too many anonymous customer creations", retryAfterSec = rl.retryAfterSec)
        }
        val res = globalTx.withTx(ctx) { txCtx -> authService.createAnonymousCustomer(txCtx) }
        // 只置 customerId；customer 对象由 nested resolver 按需查（客户端不选则不查库）
        return CreateAnonymousResult(
            customerId = res.customerId,
            accessToken = res.accessToken,
            refreshToken = res.refreshToken,
            refreshExpiresAt = res.refreshExpiresAt,
            expiresIn = res.expiresIn.toInt(),
        )
    }

    /** 按需解析 customer：仅当客户端选取 customer 字段时触发，取 parent.customerId 查库。 */
    @DgsData(parentType = "CreateAnonymousResult", field = "customer")
    fun customer(dfe: DgsDataFetchingEnvironment): Customer {
        val parent = dfe.getSource<CreateAnonymousResult>()
            ?: throw ApiError(ErrorCode.INTERNAL, "CreateAnonymousResult source missing")
        val ctx = ActionContextHolder.current()
        return customerFacade.findById(ctx, parent.customerId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "customer not found")
    }
}
