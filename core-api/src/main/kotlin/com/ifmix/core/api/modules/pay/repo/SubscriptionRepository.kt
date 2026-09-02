package com.ifmix.core.api.modules.pay.repo

import com.ifmix.core.api.entity.pay.Subscription
import com.ifmix.core.api.entity.pay.appId
import com.ifmix.core.api.entity.pay.active
import com.ifmix.core.api.entity.pay.customerId
import com.ifmix.core.api.entity.pay.id
import com.ifmix.core.api.entity.pay.originalTransactionId
import com.ifmix.core.api.entity.pay.subscriptionPxid
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.AppCrudRepoTemplate
import org.babyfish.jimmer.sql.ast.mutation.DeleteMode
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class SubscriptionRepository {
    companion object { private val tpl = AppCrudRepoTemplate(Subscription::class) }

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

    /** 合并：把 fromCustomerId 名下订阅归属改到 toCustomerId。返回改写行数。 */
    fun reassignOwner(mc: ModuleCtx, appId: UUID, fromCustomerId: UUID, toCustomerId: UUID): Int =
        mc.sql.createUpdate(Subscription::class) {
            where(table.appId eq appId)
            where(table.customerId eq fromCustomerId)
            set(table.customerId, toCustomerId)
        }.execute()

    /** restore purchases：命中已存在订阅但归属不一致时，按当前主体刷新 customerId。 */
    fun updateOwner(mc: ModuleCtx, appId: UUID, id: UUID, customerId: UUID): Int =
        mc.sql.createUpdate(Subscription::class) {
            where(table.appId eq appId)
            where(table.id eq id)
            set(table.customerId, customerId)
        }.execute()
    fun deleteById(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.deleteById(mc, appId, id)
    fun exists(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.exists(mc, appId, id)

    // ===== 阶段 6：匿名清理 =====

    /**
     * 清理前置校验：某 customer 名下是否存在 active=true 的订阅。
     * 命中则跳过删除 + 告警（避免误删「匿名却付费」边界数据）。
     */
    fun hasActiveByCustomer(mc: ModuleCtx, appId: UUID, customerId: UUID): Boolean =
        mc.sql.createQuery(Subscription::class) {
            where(table.appId eq appId)
            where(table.customerId eq customerId)
            where(table.active eq true)
            select(table.id)
        }.limit(1).execute().isNotEmpty()

    /** 物理删除某批 customer 名下的订阅（含软删列，显式 PHYSICAL 硬删避免孤儿行）。 */
    fun physicalDeleteByCustomers(mc: ModuleCtx, appId: UUID, customerIds: Collection<UUID>): Int {
        if (customerIds.isEmpty()) return 0
        return mc.sql.createDelete(Subscription::class) {
            setMode(DeleteMode.PHYSICAL)
            where(table.appId eq appId)
            where(table.customerId valueIn customerIds)
        }.execute()
    }
}
