package com.ifmix.core.api.bff.graphql.customer.customer

import com.ifmix.core.api.generated.types.CreateAnonymousResult
import com.ifmix.core.api.infra.graphql.OperationContextProvider
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.ratelimit.RateLimiter
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.auth.AuthFacade
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation

@DgsComponent
class CustomerFetcher(
    private val authService: AuthFacade,
    private val globalTx: GlobalTxRunner,
    private val ctxProvider: OperationContextProvider,
    private val rateLimiter: RateLimiter,
) {

    @DgsMutation(field = "m_customer_createAnonymous")
    fun createAnonymous(dfe: DgsDataFetchingEnvironment): CreateAnonymousResult {
        val ctx = ctxProvider.fromDfe(dfe)
        // 每 IP 60s 10 次
        val clientIp = ctx.clientIp ?: "unknown"
        if (!rateLimiter.checkFixedWindow(clientIp, RATE_LIMIT, RATE_WINDOW_SEC)) {
            throw ApiError(ErrorCode.RATE_LIMITED, "too many anonymous customer creations")
        }
        val res = globalTx.withTx(ctx) { txCtx -> authService.createAnonymous(txCtx) }
        return CreateAnonymousResult(
            accessToken = res.accessToken,
            refreshToken = res.refreshToken,
            refreshExpiresAt = res.refreshExpiresAt,
            expiresIn = res.expiresIn.toInt(),
        )
    }

    companion object {
        private const val RATE_LIMIT = 10
        private const val RATE_WINDOW_SEC = 60L
    }
}
