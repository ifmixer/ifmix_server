package com.ifmix.api.core.modules.collection

import com.ifmix.api.core.common.db.ownerCriteria
import com.ifmix.api.core.common.http.RequestContext
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query

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
    fun findDefault(ctx: RequestContext): CollectionDocument? = mongo.findOne(
        Query(
            Criteria.where("appId").`is`(ctx.appId)
                .andOperator(
                    ownerCriteria(ctx),
                    Criteria.where("isDefault").`is`(true),
                    Criteria.where("deletedAt").`is`(null),
                ),
        ),
        CollectionDocument::class.java,
    )
}
