package com.ifmix.core.api.bff.graphql.customer.customer

import com.ifmix.core.api.entity.customer.Customer
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
import com.ifmix.core.api.modules.customer.CustomerFacade
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

    @DgsMutation(field = "m_customer_createAnonymousCustomer")
    fun createAnonymousCustomer(dfe: DgsDataFetchingEnvironment): CreateAnonymousResult {
        // 建匿名号是获取首个凭证的入口，无需登录：requireActorType=null。
        val ctx = ctxProvider.fromDfe(dfe, requireActorType = null)
        // 只要求携带有效可信 iid（token 类型不限）：install token 是首装主路径，
        // 但含 iid 的 customer token 等也可 bootstrap；不再强制 type=5。iid 无效/缺失 → UNAUTHORIZED（进入事务前）。
        ctx.mustGetTokenInstallId()
        // 每 IP 60s 10 次（legacy 严格阈值独立计数器；key 带 projectId 隔离，见 attest 规格 §4.6）
        val clientIp = ctx.clientIp ?: "unknown"
        when (val rl = rateLimiter.check(
            Window.MINUTE,
            "ratelimit:${ctx.mustGetProjectId()}:anonymous:legacy:ip:min:$clientIp",
            rlProps.anonymous.legacyIpMinute,
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
