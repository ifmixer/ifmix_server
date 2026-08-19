package com.ifmix.api.core.modules.payment.repo

import com.ifmix.api.core.entity.iap.Subscription
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class SubscriptionRepository(sql: KSqlClient) : BaseAppCrudRepository<Subscription>(sql, Subscription::class) {

    fun findActiveByPxid(ctx: SvcCtx, appId: UUID, pxid: String): Subscription? {
        return sql.createQuery(Subscription::class) {
            where(table.appId eq appId)
            where(table.subscriptionPxid eq pxid)
            where(table.active eq true)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun findByPxid(ctx: SvcCtx, appId: UUID, pxid: String): Subscription? {
        return sql.createQuery(Subscription::class) {
            where(table.appId eq appId)
            where(table.subscriptionPxid eq pxid)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun findByOriginalTxn(ctx: SvcCtx, appId: UUID, originalTxnId: String): Subscription? {
        return sql.createQuery(Subscription::class) {
            where(table.appId eq appId)
            where(table.originalTransactionId eq originalTxnId)
            select(table)
        }.limit(1).execute().firstOrNull()
    }
}
