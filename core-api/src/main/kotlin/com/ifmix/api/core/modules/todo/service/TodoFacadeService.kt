package com.ifmix.api.core.modules.todo.service

import com.ifmix.api.core.generated.types.CreateTodoInput
import com.ifmix.api.core.generated.types.TodoQueryInput
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.model.todo.Todo
import com.ifmix.api.core.model.todo.TodoItem
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class TodoFacadeService(
    private val queries: TodoQueries,
    private val commands: TodoCommands,
    private val itemCommands: TodoItemCommands,
) {
    fun findById(opCtx: OperationContext, id: UUID): Todo? = queries.findById(opCtx, id)
    fun findByCursor(opCtx: OperationContext, input: TodoQueryInput): Page<Todo> = queries.findByCursor(opCtx, input)
    fun findByIds(opCtx: OperationContext, ids: List<UUID>): List<Todo> = queries.findByIds(opCtx, ids)
    fun createTodo(opCtx: OperationContext, input: CreateTodoInput): UUID = commands.create(opCtx, input)
    fun updateTodo(opCtx: OperationContext, input: UpdateTodoInput): Boolean = commands.update(opCtx, input)
    fun deleteTodo(opCtx: OperationContext, id: UUID): Boolean = commands.delete(opCtx, id)
    fun batchDeleteTodos(opCtx: OperationContext, ids: List<UUID>): Int = commands.batchDelete(opCtx, ids)
    fun updateItems(opCtx: OperationContext, input: com.ifmix.api.core.generated.types.UpdateTodoItemsMutationInput) =
        itemCommands.update(opCtx, input)
    fun findItemsByTodoIds(opCtx: OperationContext, todoIds: Collection<UUID>): List<TodoItem> =
        queries.findItemsByTodoIds(opCtx, todoIds)
}
