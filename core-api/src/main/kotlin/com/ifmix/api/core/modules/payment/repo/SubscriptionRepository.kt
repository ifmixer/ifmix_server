package com.ifmix.api.core.modules.payment.repo

import com.ifmix.api.core.entity.payment.Subscription
import com.ifmix.api.core.entity.payment.appId
import com.ifmix.api.core.entity.payment.active
import com.ifmix.api.core.entity.payment.id
import com.ifmix.api.core.entity.payment.originalTransactionId
import com.ifmix.api.core.entity.payment.subscriptionPxid
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.CrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class SubscriptionRepository {
    companion object { private val tpl = CrudRepoTemplate(Subscription::class, appId = "appId") }

    fun findActiveByPxid(mc: ModuleCtx, appId: UUID, pxid: String): Subscription? {
        return mc.sql.createQuery(Subscription::class) {
            where(table.get<UUID>("appId") eq appId)
            where(table.subscriptionPxid eq pxid)
            where(table.active eq true)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun findByPxid(mc: ModuleCtx, appId: UUID, pxid: String): Subscription? {
        return mc.sql.createQuery(Subscription::class) {
            where(table.get<UUID>("appId") eq appId)
            where(table.subscriptionPxid eq pxid)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun findByOriginalTxn(mc: ModuleCtx, appId: UUID, originalTxnId: String): Subscription? {
        return mc.sql.createQuery(Subscription::class) {
            where(table.get<UUID>("appId") eq appId)
            where(table.originalTransactionId eq originalTxnId)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun upsertSubscription(mc: ModuleCtx, entity: Subscription): Int {
        return mc.sql.entities.save(entity) {
            setKeyProps(Subscription::subscriptionPxid)
        }.totalAffectedRowCount
    }

    fun save(mc: ModuleCtx, entity: Subscription) = tpl.save(mc, entity)
    fun findById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.findById(mc, appId, id)
    fun deleteById(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.deleteById(mc, appId, id)
    fun exists(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.exists(mc, appId, id)
}
