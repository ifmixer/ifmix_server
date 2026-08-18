package com.ifmix.api.core.infra.jooq

import com.ifmix.api.core.infra.http.OperationContext
import org.jooq.DSLContext
import org.springframework.stereotype.Component

/**
 * 全局事务管理器 — DataFetcher 层使用，用于跨模块编排的事务。
 *
 * 开启全局事务后，FacadeService 中的 SvcCtx 构建会复用此 DSLContext，
 * 不会嵌套开启新的模块事务（TxRunner 检测 inGlobalTx=true → 跳过）。
 *
 * ponytail: 当前单库，globalTxDsl 和模块 dsl 相同。
 * 将来拆分后改为 saga/补偿。此函数是切换点。
 */
@Component
class GlobalTxRunner(private val dslContext: DSLContext) {

    /**
     * 开启全局事务。
     * FacadeService 检测到 opCtx.inGlobalTx=true → 复用 DSLContext，不嵌套模块事务。
     */
    fun <R> withTx(opCtx: OperationContext, body: (OperationContext) -> R): R =
        dslContext.transactionResult { config ->
            val txOpCtx = opCtx.copy(
                globalTxDsl = config.dsl(),
                inGlobalTx = true,
            )
            body(txOpCtx)
        }
}
