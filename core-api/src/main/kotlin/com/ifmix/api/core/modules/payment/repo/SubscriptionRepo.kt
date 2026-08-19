package com.ifmix.api.core.modules.payment.repo

import com.ifmix.api.core.common.db.CRUDOps
import com.ifmix.api.core.common.http.RequestContext
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.isEqualTo
import org.springframework.stereotype.Component
import java.time.Instant
import com.ifmix.api.core.modules.payment.entity.SubscriptionEntity

/**
 * subscriptions 集合仓储。
 * 基于 CRUDOps 提供基础 CRUD + 自定义 upsert（按 subscriptionPxid 去重）。
 */
@Component
class SubscriptionRepo(
    private val mongo: MongoTemplate,
) {
    private val ops = CRUDOps<SubscriptionEntity>(mongo, SubscriptionEntity::class.java)

    /**
     * upsert：按 subscriptionPxid + appId 查找，存在则更新，不存在则插入。
     * 返回文档 id。
     */
    fun upsert(ctx: RequestContext, doc: SubscriptionEntity): String {
        val query = Query(
            Criteria().andOperator(
                SubscriptionEntity::subscriptionPxid isEqualTo doc.subscriptionPxid,
                SubscriptionEntity::appId isEqualTo ObjectId(ctx.appId),
            ),
        )
        val existing = mongo.findOne(query, SubscriptionEntity::class.java)
        val now = Instant.now()

        if (existing != null) {
            // 更新已有文档
            val update = Update()
                .set(SubscriptionEntity::productId, doc.productId)
                .set(SubscriptionEntity::platform, doc.platform)
                .set(SubscriptionEntity::active, doc.active)
                .set(SubscriptionEntity::subStatus, doc.subStatus)
                .set(SubscriptionEntity::expiryDate, doc.expiryDate)
                .set(SubscriptionEntity::purchaseToken, doc.purchaseToken)
                .set(SubscriptionEntity::rawResponse, doc.rawResponse)
                .set(com.ifmix.api.core.common.db.BaseEntity::updatedAt, now)
            mongo.updateFirst(query, update, SubscriptionEntity::class.java)
            return existing.id.toHexString()
        }

        // 插入新文档
        doc.appId = ObjectId(ctx.appId)
        doc.createdAt = now
        doc.updatedAt = now
        mongo.insert(doc)
        return doc.id.toHexString()
    }

    /**
     * 按原始交易 ID 更新字段（用于退款、价格变更等后续事件）。
     */
    fun updateByOriginalTxn(
        ctx: RequestContext,
        originalTransactionId: String,
        updateFn: Update.() -> Unit,
    ): Boolean {
        val query = Query(
            Criteria().andOperator(
                SubscriptionEntity::originalTransactionId isEqualTo originalTransactionId,
                SubscriptionEntity::appId isEqualTo ObjectId(ctx.appId),
            ),
        )
        val u = Update()
        updateFn(u)
        u.set(com.ifmix.api.core.common.db.BaseEntity::updatedAt, Instant.now())
        return mongo.updateFirst(query, u, SubscriptionEntity::class.java).modifiedCount > 0
    }

    /**
     * 查找用户当前有效的活跃订阅。
     */
    fun findActiveBySubject(ctx: RequestContext, subscriptionPxid: String): SubscriptionEntity? {
        val query = Query(
            Criteria().andOperator(
                SubscriptionEntity::subscriptionPxid isEqualTo subscriptionPxid,
                SubscriptionEntity::appId isEqualTo ObjectId(ctx.appId),
                SubscriptionEntity::active isEqualTo true,
            ),
        )
        return mongo.findOne(query, SubscriptionEntity::class.java)
    }
}
