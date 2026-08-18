package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOps
import com.ifmix.api.core.jooq.tables.CoreTodo.Companion.CORE_TODO
import com.ifmix.api.core.entity.todo.Todo
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TodoRepository(private val crud: CrudRepoOps) {

    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): Todo? =
        crud.findById(ctx, CORE_TODO, CORE_TODO.APP_ID, CORE_TODO.ID, appId, id, Todo::class.java, CORE_TODO.DELETED_AT)

    fun findByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): List<Todo> =
        crud.findByIds(ctx, CORE_TODO, CORE_TODO.APP_ID, CORE_TODO.ID, appId, ids, Todo::class.java, CORE_TODO.DELETED_AT)

    fun findByCursor(ctx: SvcCtx, appId: UUID, cursor: UUID?, limit: Int): List<Todo> =
        crud.findByCursor(ctx, CORE_TODO, CORE_TODO.APP_ID, CORE_TODO.ID, appId, cursor, limit, Todo::class.java, CORE_TODO.DELETED_AT)

    fun exists(ctx: SvcCtx, appId: UUID, id: UUID): Boolean =
        crud.exists(ctx, CORE_TODO, CORE_TODO.APP_ID, CORE_TODO.ID, appId, id, CORE_TODO.DELETED_AT)

    fun insert(ctx: SvcCtx, todo: Todo) =
        crud.insert(ctx, CORE_TODO, todo)

    fun partialUpdate(ctx: SvcCtx, appId: UUID, input: UpdateTodoInput) {
        crud.partialUpdate(ctx, CORE_TODO, CORE_TODO.APP_ID, CORE_TODO.ID, appId, input.id) {
            input.set?.title?.let { set(CORE_TODO.TITLE, it) }
            input.set?.done?.let { set(CORE_TODO.DONE, it) }
            // ponytail: NOTE 列 migration 后取消注释
            // input.set?.note?.let { set(CORE_TODO.NOTE, it) }
            // if (input.unset?.contains(TodoUnsetField.NOTE) == true) { setNull(CORE_TODO.NOTE) }
        }
    }

    fun deleteById(ctx: SvcCtx, appId: UUID, id: UUID): Boolean =
        crud.deleteById(ctx, CORE_TODO, CORE_TODO.APP_ID, CORE_TODO.ID, appId, id, CORE_TODO.DELETED_AT)

    fun deleteByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): Int =
        crud.deleteByIds(ctx, CORE_TODO, CORE_TODO.APP_ID, CORE_TODO.ID, appId, ids, CORE_TODO.DELETED_AT)
}
