package com.ifmix.api.core.modules.payment.handler

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.payment.entity.StoreNotificationEntity
import com.ifmix.api.core.modules.payment.repo.SubscriptionRepo
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 订阅实体处理器——CRUD 与业务逻辑的分层。
 *
 * 持有 [SubscriptionRepo] 提供订阅查询，另持 [MongoTemplate] 处理 store_notifications 的写入。
 */
@Component
class PaymentEntityHandler(
    private val subscriptionRepo: SubscriptionRepo,
    private val mongo: MongoTemplate,
) {

    /** 写入商店通知记录用于幂等去重。 */
    fun insertStoreNotification(ctx: RequestContext, doc: StoreNotificationEntity): String {
        doc.appId = ObjectId(ctx.appId)
        doc.createdAt = Instant.now()
        doc.updatedAt = Instant.now()
        mongo.insert(doc)
        return doc.id.toHexString()
    }

    /** 委托给 subscriptionRepo 查询。 */
    fun findActiveBySubject(ctx: RequestContext, subscriptionPxid: String) =
        subscriptionRepo.findActiveBySubject(ctx, subscriptionPxid)
}
