package com.ifmix.api.core.infra.db

import com.ifmix.api.core.infra.http.OperationContext
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * ModuleCtx 工厂 — 按场景提供构建方式，替代各 Facade 里散落的 private fun mc()。
 *
 * 核心逻辑：如果 opCtx.globalTxSql 已存在（DataFetcher 开了全局事务），优先用它；
 * 否则根据 preferReader 选择 writer 或 reader。
 */
@Component
class ModuleCtxFactory(private val router: ClusterRouter) {

    /** 按 appId 路由（大多数模块用这个） */
    fun forApp(opCtx: OperationContext): ModuleCtx = ModuleCtx(
        op = opCtx,
        sql = chooseSql(opCtx, router.forApp(opCtx.mustGetAppId())),
        inTransaction = opCtx.inGlobalTx,
    )

    /** 按 auth tenant 路由 */
    fun forTenant(opCtx: OperationContext, tenantId: UUID): ModuleCtx = ModuleCtx(
        op = opCtx,
        sql = chooseSql(opCtx, router.forTenant(tenantId)),
        inTransaction = opCtx.inGlobalTx,
    )

    /** 默认（不需要路由参数，直接用 router.forApp） */
    fun default(opCtx: OperationContext): ModuleCtx = forApp(opCtx)

    private fun chooseSql(opCtx: OperationContext, pair: ClusterSqlPair): KSqlClient = when {
        opCtx.globalTxSql != null -> opCtx.globalTxSql!!  // 全局事务内，复用事务连接
        opCtx.preferReader -> pair.reader
        else -> pair.writer
    }
}
