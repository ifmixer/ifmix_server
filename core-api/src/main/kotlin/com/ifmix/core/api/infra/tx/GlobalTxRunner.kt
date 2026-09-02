package com.ifmix.core.api.infra.tx

import com.ifmix.core.api.infra.db.ClusterRouter
import com.ifmix.core.api.infra.http.OperationContext
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * 全局事务管理器 — DataFetcher 层使用，用于跨模块编排的事务。
 *
 * 开启全局事务后，ModuleCtxFactory 构建 ModuleCtx 时检测到 opCtx.globalTxSql != null，
 * 复用事务连接的 writer，不嵌套开启新的模块事务（TxRunner 检测 inTransaction=true → 跳过）。
 *
 * ponytail: 当前单库，globalTxSql 和模块 sql 相同。
 * 将来拆分后改为 saga/补偿。此函数是切换点。
 */
@Component
class GlobalTxRunner(
    private val router: ClusterRouter,
    private val txManager: PlatformTransactionManager,
) {

    /**
     * 按 appId 路由到集群 writer，开启全局事务。
     * ModuleCtxFactory 检测到 opCtx.globalTxSql != null → 复用事务 writer。
     */
    fun <R> withTx(opCtx: OperationContext, body: (OperationContext) -> R): R {
        val pair = router.forApp(opCtx.mustGetAppId())
        val txCtx = opCtx.copy(globalTxSql = pair.writer, inGlobalTx = true)
        val template = TransactionTemplate(txManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
        }
        return template.execute { body(txCtx) }!!
    }

    /** 按 authTenant 路由 */
    fun <R> withTxForTenant(opCtx: OperationContext, tenantId: java.util.UUID, body: (OperationContext) -> R): R {
        val pair = router.forTenant(tenantId)
        val txCtx = opCtx.copy(globalTxSql = pair.writer, inGlobalTx = true)
        val template = TransactionTemplate(txManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
        }
        return template.execute { body(txCtx) }!!
    }
}
