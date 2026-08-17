package com.ifmix.api.core.common.service

import com.ifmix.api.core.common.db.AppScoped
import com.ifmix.api.core.common.db.BaseDocument
import com.ifmix.api.core.common.db.CRUDRepository
import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.db.SoftDeletable
import com.ifmix.api.core.common.http.RequestContext
import org.bson.types.ObjectId
import java.time.Instant

/**
 * 通用服务：组合一个 CRUDRepository（持有，不继承）。模块 service 通过组合持有它并委托，
 * 只额外实现自己的定制逻辑（组合优于继承）。createOne 按文档能力条件盖章。
 */
class CRUDService<T : BaseDocument>(
    private val repo: CRUDRepository<T>,
) {

    /** 盖章能力字段 + 时间戳后插入，返回 Mongo 生成的 id。 */
    fun createOne(ctx: RequestContext, entity: T): String {
        val now = Instant.now()
        if (entity is AppScoped) entity.appId = ObjectId(ctx.appId)
        entity.createdAt = now
        entity.updatedAt = now
        if (entity is SoftDeletable) entity.deletedAt = null
        repo.insertOne(ctx, entity)
        return entity.id.toHexString()
    }

    fun findById(ctx: RequestContext, id: String): T? = repo.findById(ctx, id)

    fun getById(ctx: RequestContext, id: String): T = repo.getById(ctx, id)

    fun updateById(ctx: RequestContext, id: String, patch: Any): Boolean = repo.updateById(ctx, id, patch)

    fun updateByIdWithUnset(ctx: RequestContext, id: String, patch: Any, unsetFields: List<String>? = null): Boolean =
        repo.updateByIdWithUnset(ctx, id, patch, unsetFields)

    fun deleteById(ctx: RequestContext, id: String): Boolean = repo.deleteById(ctx, id)

    fun findByCursor(ctx: RequestContext, input: CursorQueryInput = CursorQueryInput()): Page<T> =
        repo.findByCursor(ctx, input)

    /** 批量按 id 查询：命中则返回，未命中跳过，返回命中文档列表。 */
    fun findByIds(ctx: RequestContext, ids: List<String>): List<T> =
        ids.mapNotNull { repo.findById(ctx, it) }

    /**
     * 批量部分更新：patches 为 id -> patch 映射，返回实际修改条数。
     * 非 app 文档或 patch 无效时该条不计入。
     */
    fun updateByIds(ctx: RequestContext, patches: Map<String, Any>): Int {
        var count = 0
        patches.forEach { (id, patch) ->
            if (repo.updateById(ctx, id, patch)) count++
        }
        return count
    }

    /**
     * 批量删除：返回实际删除条数（已删除或不存在均计为成功）。
     */
    fun deleteByIds(ctx: RequestContext, ids: List<String>): Int {
        var count = 0
        ids.forEach { id ->
            if (repo.deleteById(ctx, id)) count++
        }
        return count
    }
}
