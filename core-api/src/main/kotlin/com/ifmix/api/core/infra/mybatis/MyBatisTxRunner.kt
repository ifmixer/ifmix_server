package com.ifmix.api.core.infra.mybatis

import com.ifmix.api.core.infra.db.SvcCtx
import org.springframework.stereotype.Component

/**
 * MyBatis 事务边界接缝。对标 jOOQ 的 [com.ifmix.api.core.infra.jooq.TxRunner]。
 *
 * - REQUIRED（默认）：已有事务则复用（当前 SvcCtx.session 非 null 且 inTransaction=true），否则新建 writer session。
 * - REQUIRES_NEW：忽略现有 session，新建 writer session（autoCommit=false）。
 * - SUPPORTS：有事务则执行，无事务则非事务运行（使用 reader session）。
 * - NOT_SUPPORTED：挂起现有事务，在非事务 writer session 中运行。
 */
@Component
class MyBatisTxRunner(private val factories: MyBatisSessionFactories) {

    fun <R> withTx(svcCtx: SvcCtx, body: (SvcCtx) -> R): R =
        withTx(svcCtx, body, TxPropagation.REQUIRED)

    @Suppress("UNCHECKED_CAST")
    fun <R> withTx(
        svcCtx: SvcCtx,
        body: (SvcCtx) -> R,
        propagation: TxPropagation = TxPropagation.REQUIRED,
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

    private fun <R> newTx(svcCtx: SvcCtx, body: (SvcCtx) -> R): R {
        // 新建 writer session，autoCommit=false（手动 commit/rollback）
        val txSession = factories.writer.openSession(false)
        val txCtx = svcCtx.copy(session = txSession, inTransaction = true)
        return try {
            val result = body(txCtx)
            txSession.commit()
            result
        } catch (e: Exception) {
            txSession.rollback()
            throw e
        } finally {
            txSession.close()
        }
    }
}
