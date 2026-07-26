package com.ifmix.api.core.common.db

import com.ifmix.api.core.common.http.RequestContext
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria

/**
 * 租户仓储：每次查询强制注入 appId = ctx.appId。app 级集合一律用它。
 *
 * 这里对 CRUDRepository 的继承是框架级"同接口精化"（仅覆写 extraCriteria 钩子注入租户过滤，
 * 公开面不变，LSP 成立），与"业务 service 用组合"不冲突。
 */
open class CRUDAppRepository<T : CRUDAppDocument>(
    mongo: MongoTemplate,
    type: Class<T>,
    softDelete: Boolean,
) : CRUDRepository<T>(mongo, type, softDelete) {

    private fun tenant(ctx: RequestContext): Criteria = Criteria.where("appId").`is`(ctx.appId)

    /** 列表查询注入租户过滤。 */
    override fun extraCriteria(ctx: RequestContext): Criteria = tenant(ctx)

    /** 按 id 操作注入租户过滤——即分片键 appId，保证分片集群下单文档读写能定向到分片。 */
    override fun extraIdCriteria(ctx: RequestContext): Criteria = tenant(ctx)
}
