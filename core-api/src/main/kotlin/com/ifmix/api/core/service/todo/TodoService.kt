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
 * Todo 业务编排。
 * 权限校验（ownership）由 Controller 层负责，Service 只做数据操作。
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
        todoRepo.findTodoById(ctx, id) ?: throw ApiError(ErrorCode.NOT_FOUND)

    @Transactional
    fun createOne(ctx: OperationContext, input: TodoCreateInput): TodoView {
        val saved = todoRepo.create(ctx, input)
        return todoRepo.findTodoById(ctx, saved.id) ?: throw ApiError(ErrorCode.INTERNAL)
    }

    @Transactional
    fun updateOne(ctx: OperationContext, input: TodoUpdateInput): TodoView {
        todoRepo.update(ctx, input) ?: throw ApiError(ErrorCode.NOT_FOUND)
        return todoRepo.findTodoById(ctx, input.id) ?: throw ApiError(ErrorCode.NOT_FOUND)
    }

    @Transactional
    fun deleteOne(ctx: OperationContext, id: UUID): Boolean =
        todoRepo.deleteTodo(ctx, id)

    @Transactional
    fun deleteItems(ctx: OperationContext, itemIds: List<UUID>): Int =
        todoRepo.deleteItemsByIds(ctx, itemIds)
}
