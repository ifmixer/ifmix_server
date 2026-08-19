package com.ifmix.api.core.modules.collection

import com.ifmix.api.core.common.db.ownerCriteria
import com.ifmix.api.core.common.http.RequestContext
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.isEqualTo
import com.ifmix.api.core.modules.collection.entity.CollectionEntity

/**
 * collection 仓储：按归属查默认收藏夹。
 *
 * 查询条件：appId 分片 + ownerCriteria + isDefault=true + 未软删。
 */
class CollectionRepository(private val mongo: MongoTemplate) {

    /**
     * 查找当前用户的默认收藏夹。
     *
     * @return 默认夹文档，未找到返回 null
     */
    fun findDefault(ctx: RequestContext): CollectionEntity? = mongo.findOne(
        Query(
            Criteria().andOperator(
                CollectionEntity::appId isEqualTo ctx.appId,
                ownerCriteria(ctx),
                CollectionEntity::isDefault isEqualTo true,
                CollectionEntity::deletedAt isEqualTo null,
            ),
        ),
        CollectionEntity::class.java,
    )
}
