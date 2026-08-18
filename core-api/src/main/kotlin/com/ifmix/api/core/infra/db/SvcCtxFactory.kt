package com.ifmix.api.core.infra.db

import com.ifmix.api.core.infra.http.OperationContext
import org.springframework.stereotype.Component
import java.util.*

/**
 * SvcCtx 工厂 — 按场景提供构建方式，替代各 FacadeService 里散落的 private fun svc()。
 *
 * 核心逻辑：如果 opCtx.globalTxDsl 已存在（DataFetcher 开了全局事务），优先用它；否则走 router。
 */
@Component
class SvcCtxFactory(private val router: ClusterRouter) {

    /** 按 appId 路由（大多数模块用这个） */
    fun forApp(opCtx: OperationContext): SvcCtx = SvcCtx(
        op = opCtx,
        dsl = opCtx.globalTxDsl ?: router.forApp(opCtx.mustGetAppId()),
        inTransaction = opCtx.inGlobalTx,
    )

    /** 按 auth tenant 路由 */
    fun forAuthTenant(opCtx: OperationContext, tenantId: UUID): SvcCtx = SvcCtx(
        op = opCtx,
        dsl = opCtx.globalTxDsl ?: router.forTenant(tenantId),
        inTransaction = opCtx.inGlobalTx,
    )

    /** 默认（不需要路由参数，直接用 DEFAULT） */
    fun default(opCtx: OperationContext): SvcCtx = SvcCtx(
        op = opCtx,
        dsl = opCtx.globalTxDsl ?: SvcCtx.DEFAULT.dsl,
        inTransaction = opCtx.inGlobalTx,
    )
}
