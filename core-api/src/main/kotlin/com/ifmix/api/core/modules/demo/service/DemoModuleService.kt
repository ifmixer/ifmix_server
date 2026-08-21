package com.ifmix.api.core.modules.demo.service

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.entity.demo.Todo
import com.ifmix.api.core.entity.demo.TodoItem
import com.ifmix.api.core.entity.demo.TodoWithStats
import com.ifmix.api.core.generated.types.CreateTodoInput
import com.ifmix.api.core.generated.types.CreateTodoItemForTodoInput
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.generated.types.UpdateTodoItemInput
import com.ifmix.api.core.infra.db.SvcCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.modules.demo.service.internal.TodoEntityService
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DemoModuleService(
    private val svcCtxFactory: SvcCtxFactory,
    private val entityService: TodoEntityService,
    private val tx: TxRunner,
) {
    // ===== Todo =====

    fun createTodo(ctx: OperationContext, input: CreateTodoInput): Todo =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> entityService.createTodo(sc, input) }

    fun updateTodo(ctx: OperationContext, input: UpdateTodoInput): Todo =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> entityService.updateTodo(sc, input) }

    fun findTodoById(ctx: OperationContext, id: UUID): Todo {
        val sc = svcCtxFactory.forApp(ctx)
        return entityService.findTodoById(sc, id)
    }

    fun findTodosByIds(ctx: OperationContext, ids: List<UUID>): List<Todo> {
        val sc = svcCtxFactory.forApp(ctx)
        return entityService.findTodosByIds(sc, ids)
    }

    fun findTodosByCursor(ctx: OperationContext, cursor: String?, limit: Int?): Page<Todo> {
        val sc = svcCtxFactory.forApp(ctx)
        return entityService.findTodosByCursor(sc, cursor, limit)
    }

    fun findTodosWithStats(ctx: OperationContext, cursor: String?, limit: Int?): Page<TodoWithStats> {
        val sc = svcCtxFactory.forApp(ctx)
        return entityService.findTodosWithStats(sc, cursor, limit)
    }

    fun deleteTodo(ctx: OperationContext, id: UUID): Boolean =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> entityService.deleteTodo(sc, id) }

    fun batchDeleteTodos(ctx: OperationContext, ids: List<UUID>): Boolean =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> entityService.batchDeleteTodos(sc, ids) }

    // ===== TodoItem batch =====

    fun batchUpdateTodoItems(
        ctx: OperationContext,
        creates: List<CreateTodoItemForTodoInput>?,
        updates: List<UpdateTodoItemInput>?,
        deletes: List<UUID>?,
    ) = tx.withTx(svcCtxFactory.forApp(ctx)) { sc ->
        entityService.batchUpdateTodoItems(sc, creates, updates, deletes)
    }

    // ===== DataLoader =====

    fun findItemsByTodoIds(ctx: OperationContext, todoIds: Collection<UUID>): Map<UUID, List<TodoItem>> {
        val sc = svcCtxFactory.forApp(ctx)
        return entityService.findItemsByTodoIds(sc, todoIds)
    }
}
