package com.ifmix.api.core.common.service

import com.ifmix.api.core.common.db.AppScoped
import com.ifmix.api.core.common.db.BaseEntity
import com.ifmix.api.core.common.db.CRUDRepository
import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.db.SoftDeletable
import com.ifmix.api.core.common.http.RequestContext
import java.time.Instant

/**
 * 通用服务：组合一个 CRUDRepository（持有，不继承）。模块 service 通过组合持有它并委托，
 * 只额外实现自己的定制逻辑（组合优于继承）。createOne 按文档能力条件盖章。
 */
class CRUDService<T : BaseEntity>(
    private val repo: CRUDRepository<T>,
) {

    /** 盖章能力字段 + 时间戳后插入，返回 Mongo 生成的 id。 */
    fun createOne(ctx: RequestContext, entity: T): String {
        val now = Instant.now()
        if (entity is AppScoped) entity.appId = ctx.appId
        entity.createdAt = now
        entity.updatedAt = now
        if (entity is SoftDeletable) entity.deletedAt = null
        repo.insertOne(ctx, entity)
        return entity.id!!
    }

    fun findById(ctx: RequestContext, id: String): T? = repo.findById(ctx, id)

    fun getById(ctx: RequestContext, id: String): T = repo.getById(ctx, id)

    fun updateById(ctx: RequestContext, id: String, patch: Any): Boolean = repo.updateById(ctx, id, patch)

    fun deleteById(ctx: RequestContext, id: String): Boolean = repo.deleteById(ctx, id)

    fun findByCursor(ctx: RequestContext, input: CursorQueryInput = CursorQueryInput()): Page<T> =
        repo.findByCursor(ctx, input)
}
