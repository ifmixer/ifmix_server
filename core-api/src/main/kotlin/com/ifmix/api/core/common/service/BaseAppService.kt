package com.ifmix.api.core.common.service

import com.ifmix.api.core.common.db.BaseAppDocument
import com.ifmix.api.core.common.db.BaseAppRepository
import com.ifmix.api.core.common.db.CursorQuery
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.db.ReadOptions
import com.ifmix.api.core.common.http.RequestContext
import java.time.Instant

/** 通用租户服务基类：模块 service 继承它复用 CRUD，仅覆写定制点。 */
open class BaseAppService<T : BaseAppDocument>(
    protected val repo: BaseAppRepository<T>,
) {

    /** 盖章 appId + 时间戳后插入，返回 Mongo 生成的 id。 */
    fun createOne(ctx: RequestContext, entity: T): String {
        val now = Instant.now()
        entity.appId = ctx.appId
        entity.createdAt = now
        entity.updatedAt = now
        entity.deletedAt = null
        repo.insertOne(ctx, entity)
        return entity.id!!
    }

    fun findById(ctx: RequestContext, id: String, options: ReadOptions = ReadOptions.DEFAULT): T? =
        repo.findById(ctx, id, options)

    fun getById(ctx: RequestContext, id: String, options: ReadOptions = ReadOptions.DEFAULT): T =
        repo.getById(ctx, id, options)

    fun updateById(ctx: RequestContext, id: String, patch: Map<String, Any?>): Boolean =
        repo.updateById(ctx, id, patch)

    fun deleteById(ctx: RequestContext, id: String): Boolean =
        repo.deleteById(ctx, id)

    fun findMany(ctx: RequestContext, cursorQuery: CursorQuery): Page<T> =
        repo.findMany(ctx, cursorQuery)
}
