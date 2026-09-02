package com.ifmix.core.api.bff.graphql.customer.customer

import com.ifmix.core.api.entity.customer.Customer
import com.ifmix.core.api.generated.types.CreateAnonymousResult
import com.ifmix.core.api.infra.graphql.OperationContextProvider
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.jimmer.OperationContextHolder
import com.ifmix.core.api.infra.ratelimit.RateLimiter
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
    private val ctxProvider: OperationContextProvider,
    private val rateLimiter: RateLimiter,
) {

    @DgsMutation(field = "m_customer_createAnonymousCustomer")
    fun createAnonymousCustomer(dfe: DgsDataFetchingEnvironment): CreateAnonymousResult {
        val ctx = ctxProvider.fromDfe(dfe)
        // 每 IP 60s 10 次
        val clientIp = ctx.clientIp ?: "unknown"
        if (!rateLimiter.checkFixedWindow(clientIp, RATE_LIMIT, RATE_WINDOW_SEC)) {
            throw ApiError(ErrorCode.RATE_LIMITED, "too many anonymous customer creations")
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
        val ctx = OperationContextHolder.current()
        return customerFacade.findById(ctx, parent.customerId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "customer not found")
    }

    companion object {
        private const val RATE_LIMIT = 10
        private const val RATE_WINDOW_SEC = 60L
    }
}
