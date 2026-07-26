package com.ifmix.api.core.common.service

import com.ifmix.api.core.common.db.CRUDAppDocument
import com.ifmix.api.core.common.db.CRUDAppRepository
import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.http.RequestContext
import java.time.Instant

/**
 * 通用租户服务：组合一个 CRUDAppRepository（持有，不继承）。模块 service 通过**组合**持有它并委托，
 * 只额外实现自己的定制逻辑（组合优于继承）。
 */
class CRUDAppService<T : CRUDAppDocument>(
    private val repo: CRUDAppRepository<T>,
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

    fun findById(ctx: RequestContext, id: String): T? = repo.findById(ctx, id)

    fun getById(ctx: RequestContext, id: String): T = repo.getById(ctx, id)

    /** 部分更新：自动从 patch 对象（非空属性）或 Map 生成 Mongo $set。 */
    fun updateById(ctx: RequestContext, id: String, patch: Any): Boolean = repo.updateById(ctx, id, patch)

    fun deleteById(ctx: RequestContext, id: String): Boolean = repo.deleteById(ctx, id)

    fun findByCursor(ctx: RequestContext, input: CursorQueryInput = CursorQueryInput()): Page<T> =
        repo.findByCursor(ctx, input)
}
