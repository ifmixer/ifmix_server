package com.ifmix.api.core.common.db

import com.ifmix.api.core.common.http.RequestContext
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria

/** 租户仓储：每次查询强制注入 appId = ctx.appId。app 级集合一律用它。 */
open class BaseAppRepository<T : BaseAppDocument>(
    mongo: MongoTemplate,
    type: Class<T>,
    softDelete: Boolean,
) : BaseRepository<T>(mongo, type, softDelete) {

    override fun extraCriteria(ctx: RequestContext): Criteria =
        Criteria.where("appId").`is`(ctx.appId)
}
