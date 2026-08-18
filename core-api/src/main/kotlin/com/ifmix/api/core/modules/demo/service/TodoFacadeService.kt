package com.ifmix.api.core.modules.demo.service

import com.ifmix.api.core.generated.types.CreateTodoInput
import com.ifmix.api.core.generated.types.TodoQueryInput
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.infra.db.SvcCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.modules.demo.service.internal.TodoInternalService
import com.ifmix.api.core.modules.demo.service.internal.TodoItemInternalService
import com.ifmix.api.core.entity.todo.Todo
import com.ifmix.api.core.entity.todo.TodoItem
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class TodoFacadeService(
    private val svcCtxFactory: SvcCtxFactory,
    private val internalService: TodoInternalService,
    private val itemInternalService: TodoItemInternalService,
    private val tx: TxRunner,
) {
    fun findById(opCtx: OperationContext, id: UUID): Todo? = internalService.findById(svcCtxFactory.forApp(opCtx), id)
    fun findByCursor(opCtx: OperationContext, input: TodoQueryInput): Page<Todo> = internalService.findByCursor(svcCtxFactory.forApp(opCtx), input)
    fun findByIds(opCtx: OperationContext, ids: List<UUID>): List<Todo> = internalService.findByIds(svcCtxFactory.forApp(opCtx), ids)
    fun createTodo(opCtx: OperationContext, input: CreateTodoInput): UUID = tx.withTx(svcCtxFactory.forApp(opCtx)) { sc ->
        internalService.create(sc, input)
    }
    fun updateTodo(opCtx: OperationContext, input: UpdateTodoInput): Boolean = tx.withTx(svcCtxFactory.forApp(opCtx)) { sc ->
        internalService.update(sc, input)
    }
    fun deleteTodo(opCtx: OperationContext, id: UUID): Boolean = tx.withTx(svcCtxFactory.forApp(opCtx)) { sc ->
        internalService.delete(sc, id)
    }
    fun batchDeleteTodos(opCtx: OperationContext, ids: List<UUID>): Int = tx.withTx(svcCtxFactory.forApp(opCtx)) { sc ->
        internalService.batchDelete(sc, ids)
    }
    fun updateItems(opCtx: OperationContext, input: com.ifmix.api.core.generated.types.UpdateTodoItemsMutationInput) =
        tx.withTx(svcCtxFactory.forApp(opCtx)) { sc -> itemInternalService.update(sc, input) }
    fun findItemsByTodoIds(opCtx: OperationContext, todoIds: Collection<UUID>): List<TodoItem> =
        internalService.findItemsByTodoIds(svcCtxFactory.forApp(opCtx), todoIds)
}
