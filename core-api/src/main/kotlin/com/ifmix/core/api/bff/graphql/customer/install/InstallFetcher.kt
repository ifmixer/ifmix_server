package com.ifmix.core.api.bff.graphql.customer.install

import com.ifmix.core.api.generated.types.CreateInstallResult
import com.ifmix.core.api.generated.types.UpdateInstallResult
import com.ifmix.core.api.infra.graphql.ActionContextProvider
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.ratelimit.RateLimitProperties
import com.ifmix.core.api.infra.ratelimit.RateLimiter
import com.ifmix.core.api.infra.ratelimit.RateLimitResult
import com.ifmix.core.api.infra.ratelimit.Window
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
    private val rlProps: RateLimitProperties,
) {
    @DgsMutation(field = "m_install_createInstall")
    fun createInstall(dfe: DgsDataFetchingEnvironment, @InputArgument input: Map<String, Any?>?): CreateInstallResult {
        // 无鉴权：requireActorType=null
        val ctx = ctxProvider.fromDfe(dfe, requireActorType = null)
        val clientIp = ctx.clientIp ?: "unknown"
        // 入口短窗口 100/60s/IP（key 带 projectId 隔离；阈值/限流策略见 attest 规格 §4.6）
        when (val rl = rateLimiter.check(
            Window.MINUTE,
            "ratelimit:${ctx.mustGetProjectId()}:install:ip:min:$clientIp",
            rlProps.install.ipMinute,
        )) {
            RateLimitResult.Allowed, RateLimitResult.Degraded -> Unit
            is RateLimitResult.Limited ->
                throw ApiError(ErrorCode.RATE_LIMITED, "too many createInstall", retryAfterSec = rl.retryAfterSec)
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
                (input["scanResultNotiEnabled"] as? Boolean),
                (input["deepResearchNotiEnabled"] as? Boolean),
            )
        }
        return UpdateInstallResult(success = ok)
    }
}
