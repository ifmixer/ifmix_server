package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.common.db.CRUDOps
import com.ifmix.api.core.common.db.RepoCtx
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
    fun findById(ctx: RepoCtx, appId: ObjectId, id: ObjectId): TodoEntity? =
        crudOps.findById(ctx, appId, id.toHexString())

    fun findByIds(ctx: RepoCtx, appId: ObjectId, ids: List<ObjectId>): List<TodoEntity> =
        crudOps.findByIds(ctx, appId, ids.map { it.toHexString() })

    fun findByCursor(ctx: RepoCtx, appId: ObjectId, input: CursorQueryInput): Page<TodoEntity> =
        crudOps.findByCursor(ctx, appId, input)

    fun insert(ctx: RepoCtx, entity: TodoEntity): ObjectId {
        crudOps.insertOne(ctx, entity)
        return entity.id
    }

    fun updateById(ctx: RepoCtx, appId: ObjectId, id: ObjectId, patch: Any, unsetFields: List<String>? = null): Boolean =
        crudOps.updateByIdWithUnset(ctx, appId, id.toHexString(), patch, unsetFields)

    fun deleteById(ctx: RepoCtx, appId: ObjectId, id: ObjectId): Boolean =
        crudOps.deleteById(ctx, appId, id.toHexString())

    fun deleteByIds(ctx: RepoCtx, appId: ObjectId, ids: List<ObjectId>): Int =
        crudOps.deleteByIds(ctx, appId, ids.map { it.toHexString() })

    fun updateByIds(ctx: RepoCtx, appId: ObjectId, patches: Map<String, Any>): Int =
        crudOps.updateByIds(ctx, appId, patches)
}
