package com.ifmix.api.core.modules.payment.repo

import com.ifmix.api.core.entity.iap.Subscription
import com.ifmix.api.core.entity.iap.appId
import com.ifmix.api.core.entity.iap.subscriptionPxid
import com.ifmix.api.core.entity.iap.originalTransactionId
import com.ifmix.api.core.entity.iap.active
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID
import com.ifmix.api.core.entity.iap.id

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
