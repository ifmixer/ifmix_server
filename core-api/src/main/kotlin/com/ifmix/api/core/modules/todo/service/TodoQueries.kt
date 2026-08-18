package com.ifmix.api.core.modules.todo.service

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.generated.types.TodoQueryInput
import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.service.CrudServiceOps
import com.ifmix.api.core.infra.service.CrudServiceOpsFactory
import com.ifmix.api.core.model.todo.Todo
import com.ifmix.api.core.model.todo.TodoItem
import com.ifmix.api.core.modules.todo.repo.TodoItemRepository
import com.ifmix.api.core.modules.todo.repo.TodoRepository
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class TodoQueries(
    private val repo: TodoRepository,
    private val itemRepo: TodoItemRepository,
    factory: CrudServiceOpsFactory,
) {
    private val ops: CrudServiceOps<Todo> = factory.create(Todo::class.java, "todo") { it.id }
    private fun svc(opCtx: OperationContext) = SvcCtx(op = opCtx, dsl = opCtx.globalTxDsl ?: SvcCtx.DEFAULT.dsl)

    fun findById(opCtx: OperationContext, id: UUID): Todo? = ops.findById(svc(opCtx), id, repo::findById)
    fun findByIds(opCtx: OperationContext, ids: List<UUID>): List<Todo> = ops.findByIds(svc(opCtx), ids, repo::findByIds)
    fun findByCursor(opCtx: OperationContext, input: TodoQueryInput): Page<Todo> = ops.findByCursor(svc(opCtx), input.cursor, input.limit, repo::findByCursor)
    fun findItemsByTodoIds(opCtx: OperationContext, todoIds: Collection<UUID>): List<TodoItem> =
        itemRepo.findByTodoIds(svc(opCtx), todoIds)
}
