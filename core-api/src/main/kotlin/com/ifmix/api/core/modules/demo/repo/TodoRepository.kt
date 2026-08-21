package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.entity.demo.Todo
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOpsFactory
import com.ifmix.api.core.jooq.tables.CoreTodo.Companion.CORE_TODO
import org.jooq.TableField
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TodoRepository(factory: CrudRepoOpsFactory) {

    companion object {
        val FIELD_MAP: Map<String, TableField<*, *>> = mapOf(
            Todo::id.name to CORE_TODO.ID,
            Todo::appId.name to CORE_TODO.APP_ID,
            Todo::installId.name to CORE_TODO.INSTALL_ID,
            Todo::userId.name to CORE_TODO.USER_ID,
            Todo::title.name to CORE_TODO.TITLE,
            Todo::done.name to CORE_TODO.DONE,
            Todo::createdAt.name to CORE_TODO.CREATED_AT,
            Todo::updatedAt.name to CORE_TODO.UPDATED_AT,
        )
    }

    private val crud = factory.create(
        table = CORE_TODO,
        idField = CORE_TODO.ID,
        appIdField = CORE_TODO.APP_ID,
        type = Todo::class.java,
        deletedAtField = CORE_TODO.DELETED_AT,
    )

    fun insert(ctx: SvcCtx, todo: Todo) = crud.insert(ctx, todo)

    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): Todo? = crud.findById(ctx, appId, id)

    fun findByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): List<Todo> = crud.findByIds(ctx, appId, ids)

    fun findByCursor(ctx: SvcCtx, appId: UUID, cursor: UUID?, limit: Int): Page<Todo> {
        val items = crud.findByCursor(ctx, appId, cursor, limit + 1)
        return Page.of(items, limit) { it.id.toString() }
    }

    fun deleteById(ctx: SvcCtx, appId: UUID, id: UUID): Boolean = crud.deleteById(ctx, appId, id)

    fun deleteByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): Int = crud.deleteByIds(ctx, appId, ids)

    fun partialUpdate(ctx: SvcCtx, appId: UUID, id: UUID, block: org.jooq.UpdateSetMoreStep<org.jooq.Record>.() -> Unit) =
        crud.partialUpdate(ctx, appId, id, block)
}
