package com.ifmix.api.core.infra.jooq

import com.ifmix.api.core.infra.db.SvcCtx
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
        svcCtx: SvcCtx,
        propagation: TxPropagation = TxPropagation.REQUIRED,
        body: (SvcCtx) -> R,
    ): R = when (propagation) {
        TxPropagation.REQUIRED -> {
            if (svcCtx.inTransaction) body(svcCtx)
            else newTx(svcCtx, body)
        }
        TxPropagation.REQUIRES_NEW -> {
            newTx(svcCtx.copy(inTransaction = false), body)
        }
        TxPropagation.SUPPORTS -> {
            body(svcCtx)
        }
        TxPropagation.NOT_SUPPORTED -> {
            if (svcCtx.inTransaction) {
                body(svcCtx.copy(inTransaction = false))
            } else {
                body(svcCtx)
            }
        }
    }

    private fun <R> newTx(svcCtx: SvcCtx, body: (SvcCtx) -> R): R =
        svcCtx.dsl.transactionResult { config ->
            body(svcCtx.copy(dsl = config.dsl(), inTransaction = true))
        }
}
