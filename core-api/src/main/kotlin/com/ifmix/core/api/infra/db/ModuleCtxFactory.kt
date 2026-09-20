package com.ifmix.core.api.infra.db

import com.ifmix.core.api.infra.http.ActionContext
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * ModuleCtx 工厂 — 按场景提供构建方式，替代各 Facade 里散落的 private fun mc()。
 *
 * 核心逻辑：如果 actionCtx.globalTxSql 已存在（DataFetcher 开了全局事务），优先用它；
 * 否则根据 preferReader 选择 writer 或 reader。
 */
@Component
class ModuleCtxFactory(private val router: ClusterRouter) {

    /** 按 projectId 路由（大多数模块用这个） */
    fun forProject(actionCtx: ActionContext): ModuleCtx = ModuleCtx(
        action = actionCtx,
        sql = chooseSql(actionCtx, router.forProject(actionCtx.mustGetProjectId())),
        inTransaction = actionCtx.inGlobalTx,
    )

    /** 按 auth tenant 路由 */
    fun forTenant(actionCtx: ActionContext, tenantId: UUID): ModuleCtx = ModuleCtx(
        action = actionCtx,
        sql = chooseSql(actionCtx, router.forTenant(tenantId)),
        inTransaction = actionCtx.inGlobalTx,
    )

    /** 默认（不需要路由参数，直接用 router.forProject） */
    fun default(actionCtx: ActionContext): ModuleCtx = forProject(actionCtx)

    private fun chooseSql(actionCtx: ActionContext, pair: ClusterSqlPair): KSqlClient = when {
        actionCtx.globalTxSql != null -> actionCtx.globalTxSql!!  // 全局事务内，复用事务连接
        actionCtx.preferReader -> pair.reader
        else -> pair.writer
    }
}
