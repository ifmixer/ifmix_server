package com.ifmix.api.core.modules.payment.repo

import com.ifmix.api.core.entity.payment.Subscription
import com.ifmix.api.core.entity.payment.appId
import com.ifmix.api.core.entity.payment.subscriptionPxid
import com.ifmix.api.core.entity.payment.originalTransactionId
import com.ifmix.api.core.entity.payment.active
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID
import com.ifmix.api.core.entity.payment.id

@Repository
class SubscriptionRepository(sql: KSqlClient) : BaseAppCrudRepository<Subscription>(sql, Subscription::class) {

    fun findActiveByPxid(ctx: ModuleCtx, appId: UUID, pxid: String): Subscription? {
        return ctx.sql.createQuery(Subscription::class) {
            where(table.get<UUID>("appId") eq appId)
            where(table.subscriptionPxid eq pxid)
            where(table.active eq true)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun findByPxid(ctx: ModuleCtx, appId: UUID, pxid: String): Subscription? {
        return ctx.sql.createQuery(Subscription::class) {
            where(table.get<UUID>("appId") eq appId)
            where(table.subscriptionPxid eq pxid)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun findByOriginalTxn(ctx: ModuleCtx, appId: UUID, originalTxnId: String): Subscription? {
        return ctx.sql.createQuery(Subscription::class) {
            where(table.get<UUID>("appId") eq appId)
            where(table.originalTransactionId eq originalTxnId)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun upsertSubscription(ctx: ModuleCtx, entity: Subscription) {
        ctx.sql.entities.save(entity) {
            setKeyProps(Subscription::subscriptionPxid)
        }
    }
}
