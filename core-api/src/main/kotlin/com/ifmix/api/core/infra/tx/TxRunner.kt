package com.ifmix.api.core.infra.tx

import com.ifmix.api.core.infra.db.SvcCtx
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.support.TransactionTemplate

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

fun TxPropagation.toSpring(): Int = when (this) {
    TxPropagation.REQUIRED -> TransactionDefinition.PROPAGATION_REQUIRED
    TxPropagation.REQUIRES_NEW -> TransactionDefinition.PROPAGATION_REQUIRES_NEW
    TxPropagation.SUPPORTS -> TransactionDefinition.PROPAGATION_SUPPORTS
    TxPropagation.NOT_SUPPORTED -> TransactionDefinition.PROPAGATION_NOT_SUPPORTED
}

/**
 * 事务边界接缝。Service 通过 withTx 进入事务。
 *
 * 默认 REQUIRED（和 Spring @Transactional 一致）：有事务则复用，无则新建。
 */
@Component
class TxRunner(private val txManager: org.springframework.transaction.PlatformTransactionManager) {

    fun <R> withTx(
        svcCtx: SvcCtx,
        propagation: TxPropagation = TxPropagation.REQUIRED,
        body: (SvcCtx) -> R,
    ): R = when (propagation) {
        TxPropagation.REQUIRED -> {
            if (svcCtx.inTransaction) body(svcCtx)
            else newTx(svcCtx, body, TxPropagation.REQUIRED)
        }
        TxPropagation.REQUIRES_NEW -> {
            newTx(svcCtx.copy(inTransaction = false), body, TxPropagation.REQUIRES_NEW)
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

    private fun <R> newTx(svcCtx: SvcCtx, body: (SvcCtx) -> R, prop: TxPropagation): R {
        val template = TransactionTemplate(txManager).apply {
            this.propagationBehavior = prop.toSpring()
        }
        return template.execute { body(svcCtx.copy(inTransaction = true)) }!!
    }
}
