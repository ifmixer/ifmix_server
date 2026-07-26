package com.ifmix.api.core.modules.iap

import com.ifmix.api.core.common.http.RequestContext
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * subscriptions 集合的读写操作。
 * 不继承 CRUDRepository——订阅有自定义的上插/查询逻辑，不走通用 by-id 模式。
 */
@Component
class SubscriptionRepo(
    private val mongo: MongoTemplate,
) {

    /**
     * upsert：按 subscriptionPxid + appId 查找，存在则更新，不存在则插入。
     * 返回文档 id。
     */
    fun upsert(ctx: RequestContext, doc: SubscriptionDocument): String {
        val query = Query(
            Criteria.where("subscriptionPxid").`is`(doc.subscriptionPxid)
                .and("appId").`is`(ctx.appId),
        )
        val existing = mongo.findOne(query, SubscriptionDocument::class.java)
        val now = Instant.now()

        if (existing != null) {
            // 更新已有文档
            val update = Update()
                .set("productId", doc.productId)
                .set("platform", doc.platform)
                .set("active", doc.active)
                .set("subStatus", doc.subStatus)
                .set("expiryDate", doc.expiryDate)
                .set("purchaseToken", doc.purchaseToken)
                .set("rawResponse", doc.rawResponse)
                .set("updatedAt", now)
            mongo.updateFirst(query, update, SubscriptionDocument::class.java)
            return existing.id!!
        }

        // 插入新文档
        doc.appId = ctx.appId
        doc.createdAt = now
        doc.updatedAt = now
        mongo.insert(doc)
        return doc.id!!
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
            Criteria.where("originalTransactionId").`is`(originalTransactionId)
                .and("appId").`is`(ctx.appId),
        )
        val u = Update()
        updateFn(u)
        u.set("updatedAt", Instant.now())
        return mongo.updateFirst(query, u, SubscriptionDocument::class.java).modifiedCount > 0
    }

    /**
     * 查找用户当前有效的活跃订阅。
     * 最多返回一条（同一用户不应有多个 ACTIVE 订阅，调用方应做幂等）。
     */
    fun findActiveBySubject(ctx: RequestContext, subscriptionPxid: String): SubscriptionDocument? {
        val query = Query(
            Criteria.where("subscriptionPxid").`is`(subscriptionPxid)
                .and("appId").`is`(ctx.appId)
                .and("active").`is`(true),
        )
        return mongo.findOne(query, SubscriptionDocument::class.java)
    }
}
