package com.ifmix.core.api.bff.graphql.customer.install

import com.ifmix.core.api.generated.types.CreateInstallResult
import com.ifmix.core.api.generated.types.UpdateInstallResult
import com.ifmix.core.api.infra.graphql.ActionContextProvider
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.ratelimit.RateLimiter
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.install.InstallFacade
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.InputArgument

@DgsComponent
class InstallFetcher(
    private val installFacade: InstallFacade,
    private val globalTx: GlobalTxRunner,
    private val ctxProvider: ActionContextProvider,
    private val rateLimiter: RateLimiter,
) {
    @DgsMutation(field = "m_install_createInstall")
    fun createInstall(dfe: DgsDataFetchingEnvironment, @InputArgument input: Map<String, Any?>?): CreateInstallResult {
        // 无鉴权：requireActorType=null
        val ctx = ctxProvider.fromDfe(dfe, requireActorType = null)
        val clientIp = ctx.clientIp ?: "unknown"
        if (!rateLimiter.checkFixedWindow("install:$clientIp", RATE_LIMIT, RATE_WINDOW_SEC)) {
            throw ApiError(ErrorCode.RATE_LIMITED, "too many createInstall")
        }
        @Suppress("UNCHECKED_CAST")
        val deviceInfo = input?.get("deviceInfo") as? Map<String, Any?>
        val res = globalTx.withTx(ctx) { txCtx -> installFacade.createInstall(txCtx, deviceInfo) }
        return CreateInstallResult(installId = res.installId, installToken = res.installToken)
    }

    @DgsMutation(field = "m_install_updateInstall")
    fun updateInstall(dfe: DgsDataFetchingEnvironment, @InputArgument input: Map<String, Any?>): UpdateInstallResult {
        // 需 token（install 或 customer），取 iid
        val ctx = ctxProvider.fromDfe(dfe, requireActorType = null)
        val installId = ctx.tokenInstallId
            ?: throw ApiError(ErrorCode.UNAUTHORIZED, "install token required")
        @Suppress("UNCHECKED_CAST")
        val deviceInfo = input["deviceInfo"] as? Map<String, Any?>
        val ok = globalTx.withTx(ctx) { txCtx ->
            installFacade.updateInstall(
                txCtx, installId,
                input["firebaseInstallId"] as? String,
                input["fcmToken"] as? String,
                deviceInfo,
            )
        }
        return UpdateInstallResult(success = ok)
    }

    companion object {
        private const val RATE_LIMIT = 10
        private const val RATE_WINDOW_SEC = 60L
    }
}
