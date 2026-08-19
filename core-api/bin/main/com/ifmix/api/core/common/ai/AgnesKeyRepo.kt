package com.ifmix.api.core.common.ai

import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query

/**
 * Agnes key 仓储。
 *
 * 提供启用状态的 key 列表查询（deletedAt = null）。
 */
class AgnesKeyRepo(private val mongo: MongoTemplate) {

    /**
     * 加载所有启用的 key（deletedAt = null），按 type 排序。
     */
    fun loadEnabled(): List<AgnesKeyEntity> {
        return mongo.find(
            Query(Criteria.where("deletedAt").`is`(null)),
            AgnesKeyEntity::class.java,
        )
    }
}
