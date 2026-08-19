package com.ifmix.api.core.infra.tx

import com.ifmix.api.core.infra.http.OperationContext
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 全局事务管理器 — DataFetcher 层使用，用于跨模块编排的事务。
 *
 * 开启全局事务后，ModuleService 中的 SvcCtx 构建会复用此 KSqlClient，
 * 不会嵌套开启新的模块事务（TxRunner 检测 inGlobalTx=true → 跳过）。
 *
 * ponytail: 当前单库，globalTxSql 和模块 sql 相同。
 * 将来拆分后改为 saga/补偿。此函数是切换点。
 */
@Component
class GlobalTxRunner(
    private val sqlClient: KSqlClient,
    private val txManager: PlatformTransactionManager,
) {

    /**
     * 开启全局事务。
     * ModuleService 检测到 opCtx.inGlobalTx=true → 复用 KSqlClient，不嵌套模块事务。
     */
    fun <R> withTx(opCtx: OperationContext, body: (OperationContext) -> R): R {
        val txCtx = opCtx.copy(globalTxSql = sqlClient, inGlobalTx = true)
        val template = TransactionTemplate(txManager).apply {
            propagationBehavior = org.springframework.transaction.Propagation.REQUIRED
        }
        return template.execute { body(txCtx) }!!
    }
}
