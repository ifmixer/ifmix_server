package com.ifmix.api.core.modules.cms.repo

import com.ifmix.api.core.common.db.CRUDOps
import com.ifmix.api.core.common.http.RepoCtx
import com.ifmix.api.core.modules.cms.entity.FeedbackEntity
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Component

/** feedback 集合仓储。追加式写入，不软删。 */
@Component
class FeedbackRepository(mongo: MongoTemplate) {
    private val ops = CRUDOps(mongo, FeedbackEntity::class.java)

    fun insert(ctx: RepoCtx, entity: FeedbackEntity) {
        ops.insertOne(ctx, entity.appId.toHexString(), entity)
    }
}
