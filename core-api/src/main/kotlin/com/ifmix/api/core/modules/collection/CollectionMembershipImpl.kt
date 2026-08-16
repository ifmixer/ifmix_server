package com.ifmix.api.core.modules.collection

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.antique.CollectionMembership
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.isEqualTo

/**
 * CollectionMembership 端口实现：查询 collection_item 判断 scanRecordId 是否在默认夹中。
 *
 * 注册为 [CollectionMembership] bean，供 antique 模块可选注入。
 */
class CollectionMembershipImpl(
    private val mongo: MongoTemplate,
    private val collectionRepo: CollectionRepository,
) : CollectionMembership {

    override fun isCollected(ctx: RequestContext, scanRecordId: String): Boolean {
        val cid = collectionRepo.findDefault(ctx)?.id ?: return false
        return mongo.exists(
            Query(
                Criteria().andOperator(
                    CollectionItemDocument::appId isEqualTo ctx.appId,
                    CollectionItemDocument::collectionId isEqualTo cid,
                    CollectionItemDocument::scanRecordId isEqualTo org.bson.types.ObjectId(scanRecordId),
                    CollectionItemDocument::deletedAt isEqualTo null,
                ),
            ),
            CollectionItemDocument::class.java,
        )
    }
}
