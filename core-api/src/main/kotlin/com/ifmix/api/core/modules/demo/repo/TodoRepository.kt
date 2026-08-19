package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.common.db.CRUDOps
import com.ifmix.api.core.common.http.RepoCtx
import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.modules.demo.entity.TodoEntity
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

/**
 * Todo 仓储。持有 CRUDOps 实例获得基础 CRUD + 游标分页。
 */
@Component
class TodoRepository(
    private val crudOps: CRUDOps<TodoEntity>,
) {
    fun findById(ctx: RepoCtx, appId: String, id: String): TodoEntity? =
        crudOps.findById(ctx, appId, id)

    fun findByIds(ctx: RepoCtx, appId: String, ids: List<String>): List<TodoEntity> =
        crudOps.findByIds(ctx, appId, ids)

    fun findByCursor(ctx: RepoCtx, appId: String, input: CursorQueryInput): Page<TodoEntity> =
        crudOps.findByCursor(ctx, appId, input)

    fun insert(ctx: RepoCtx, appId: String, entity: TodoEntity): String {
        crudOps.insertOne(ctx, appId, entity)
        return entity.id.toHexString()
    }

    fun updateById(ctx: RepoCtx, appId: String, id: String, patch: Any, unsetFields: List<String>? = null): Boolean =
        crudOps.updateByIdWithUnset(ctx, appId, id, patch, unsetFields)

    fun deleteById(ctx: RepoCtx, appId: String, id: String): Boolean =
        crudOps.deleteById(ctx, appId, id)

    fun deleteByIds(ctx: RepoCtx, appId: String, ids: List<String>): Int =
        crudOps.deleteByIds(ctx, appId, ids)

    fun updateByIds(ctx: RepoCtx, appId: String, patches: Map<String, Any>): Int =
        crudOps.updateByIds(ctx, appId, patches)
}
