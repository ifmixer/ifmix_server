package com.ifmix.api.core.service.todo

import com.ifmix.api.core.entity.todo.Todo
import com.ifmix.api.core.entity.todo.dto.TodoCreateInput
import com.ifmix.api.core.entity.todo.dto.TodoUpdateInput
import com.ifmix.api.core.entity.todo.dto.TodoView
import com.ifmix.api.core.infra.db.CursorQueryInput
import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.repository.todo.TodoRepository
import com.ifmix.api.core.service.base.BaseAppCrudService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Todo 业务逻辑。标准 CRUD 由 BaseAppCrudService 提供。
 * 对外（BFF）一律以 [TodoView] 交互，租户由 ctx.appId 强制约束。
 */
@Service
class TodoService(
    private val todoRepo: TodoRepository,
) : BaseAppCrudService<Todo>(todoRepo) {

    @Transactional(readOnly = true)
    fun findTodoByCursor(ctx: OperationContext, input: CursorQueryInput): Page<TodoView> =
        todoRepo.findViewByCursor(ctx, ctx.mustGetAppId(), TodoView::class, input)

    @Transactional(readOnly = true)
    fun getTodo(ctx: OperationContext, id: UUID): TodoView =
        todoRepo.findTodoById(ctx, ctx.mustGetAppId(), id) ?: throw ApiError(ErrorCode.NOT_FOUND)

    @Transactional
    fun createOne(ctx: OperationContext, input: TodoCreateInput): TodoView {
        val appId = ctx.mustGetAppId()
        val saved = todoRepo.create(ctx, appId, input)
        return todoRepo.findTodoById(ctx, appId, saved.id) ?: throw ApiError(ErrorCode.INTERNAL)
    }

    @Transactional
    fun updateOne(ctx: OperationContext, input: TodoUpdateInput): TodoView {
        val appId = ctx.mustGetAppId()
        todoRepo.update(ctx, appId, input) ?: throw ApiError(ErrorCode.NOT_FOUND)
        return todoRepo.findTodoById(ctx, appId, input.id) ?: throw ApiError(ErrorCode.NOT_FOUND)
    }

    @Transactional
    fun deleteOne(ctx: OperationContext, id: UUID): Boolean =
        todoRepo.deleteForApp(ctx, ctx.mustGetAppId(), id)

    @Transactional
    fun deleteItems(ctx: OperationContext, itemIds: List<UUID>): Int =
        todoRepo.deleteItemsByIds(ctx, ctx.mustGetAppId(), itemIds)
}
