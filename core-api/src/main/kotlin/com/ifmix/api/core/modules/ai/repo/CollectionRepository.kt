package com.ifmix.api.core.modules.ai.repo

import com.ifmix.api.core.common.db.CRUDOps
import com.ifmix.api.core.common.db.MongoClusterResolver
import com.ifmix.api.core.common.http.RepoCtx
import com.ifmix.api.core.common.http.RequestContext
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.isEqualTo
import com.ifmix.api.core.modules.ai.entity.CollectionEntity
import com.ifmix.api.core.modules.ai.repo.CollectionRepository

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
                com.ifmix.api.core.common.db.ownerCriteria(ctx),
                CollectionEntity::isDefault isEqualTo true,
                CollectionEntity::deletedAt isEqualTo null,
            ),
        ),
        CollectionEntity::class.java,
    )
}
