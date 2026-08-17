package com.ifmix.api.core.infra.jooq

import com.ifmix.api.core.infra.http.OperationContext
import org.springframework.stereotype.Component

/**
 * 事务传播行为（对标 Spring Propagation）。
 */
enum class TxPropagation {
    /** 有事务则加入，无则新建（默认）。 */
    REQUIRED,
    /** 总是新建事务（挂起外层事务）。 */
    REQUIRES_NEW,
    /** 有事务则加入，无则非事务执行。 */
    SUPPORTS,
    /** 无事务执行，有事务则挂起。 */
    NOT_SUPPORTED,
}

/**
 * 事务边界接缝。Service 通过 withTx 进入事务。
 *
 * 默认 REQUIRED（和 Spring @Transactional 一致）：有事务则复用，无则新建。
 */
@Component
class TxRunner {

    fun <R> withTx(
        ctx: OperationContext,
        propagation: TxPropagation = TxPropagation.REQUIRED,
        body: (OperationContext) -> R,
    ): R = when (propagation) {
        TxPropagation.REQUIRED -> {
            if (ctx.repoCtx.inTransaction) body(ctx)
            else newTx(ctx, body)
        }
        TxPropagation.REQUIRES_NEW -> {
            // 总是开新事务（即使外层已有事务，也在新连接上开独立事务）
            newTx(ctx.copy(repoCtx = ctx.repoCtx.copy(inTransaction = false)), body)
        }
        TxPropagation.SUPPORTS -> {
            // 有就用，没有就裸跑
            body(ctx)
        }
        TxPropagation.NOT_SUPPORTED -> {
            // 强制非事务（如果当前在事务中，用原始 dsl 新开非事务上下文）
            if (ctx.repoCtx.inTransaction) {
                body(ctx.copy(repoCtx = ctx.repoCtx.copy(inTransaction = false)))
            } else {
                body(ctx)
            }
        }
    }

    private fun <R> newTx(ctx: OperationContext, body: (OperationContext) -> R): R =
        ctx.repoCtx.dsl.transactionResult { config ->
            val txCtx = ctx.copy(
                repoCtx = ctx.repoCtx.copy(dsl = config.dsl(), inTransaction = true)
            )
            body(txCtx)
        }
}
