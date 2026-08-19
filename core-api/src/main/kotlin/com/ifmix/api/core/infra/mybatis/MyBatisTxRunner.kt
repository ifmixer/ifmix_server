package com.ifmix.api.core.infra.mybatis

import com.ifmix.api.core.infra.db.SvcCtx
import org.springframework.stereotype.Component

/**
 * MyBatis 事务管理器 — 对标 jOOQ 的 [com.ifmix.api.core.infra.jooq.TxRunner]。
 *
 * - REQUIRED（默认）：已有事务则复用，否则新建 writer session（autoCommit=false）。
 * - SUPPORTS：有事务则执行，无事务则非事务运行。
 */
@Component
class MyBatisTxRunner(private val factories: MyBatisSessionFactories) {

    /** 新建 writer session，手动 commit/rollback。 */
    fun <R> withTx(svcCtx: SvcCtx, body: (SvcCtx) -> R): R {
        if (svcCtx.inTransaction) return body(svcCtx)

        val txSession = factories.writer.openSession(false) // autoCommit=false
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
