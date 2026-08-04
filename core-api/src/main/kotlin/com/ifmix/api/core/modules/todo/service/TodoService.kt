package com.ifmix.api.core.modules.todo.service

import com.ifmix.api.core.entity.todo.Todo
import com.ifmix.api.core.entity.todo.dto.TodoCreateInput
import com.ifmix.api.core.entity.todo.dto.TodoUpdateInput
import com.ifmix.api.core.entity.todo.dto.TodoView
import com.ifmix.api.core.infra.db.CursorQueryInput
import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.infra.service.BaseAppCrudService
import com.ifmix.api.core.modules.todo.repo.TodoRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TodoService(
    private val todoRepo: TodoRepository,
) : BaseAppCrudService<Todo>(todoRepo) {

    @Transactional(readOnly = true)
    fun findTodoByCursor(ctx: OperationContext, input: CursorQueryInput): Page<TodoView> =
        todoRepo.findViewByCursor(ctx.repoCtx, ctx.mustGetAppId(), TodoView::class, input)

    @Transactional(readOnly = true)
    fun getTodo(ctx: OperationContext, id: UUID): TodoView =
        todoRepo.findTodoById(ctx.repoCtx, ctx.mustGetAppId(), id) ?: throw ApiError(ErrorCode.NOT_FOUND)

    @Transactional
    fun createOne(ctx: OperationContext, input: TodoCreateInput): TodoView {
        val appId = ctx.mustGetAppId()
        val saved = todoRepo.create(ctx.repoCtx, appId, ctx.installId, ctx.userId, input)
        return todoRepo.findTodoById(ctx.repoCtx, appId, saved.id) ?: throw ApiError(ErrorCode.INTERNAL)
    }

    @Transactional
    fun updateOne(ctx: OperationContext, input: TodoUpdateInput): Boolean {
        return todoRepo.update(ctx.repoCtx, ctx.mustGetAppId(), input) != null
    }

    @Transactional
    fun deleteOne(ctx: OperationContext, id: UUID): Boolean =
        todoRepo.deleteTodo(ctx.repoCtx, ctx.mustGetAppId(), id)

    @Transactional(readOnly = true)
    fun getByIds(ctx: OperationContext, ids: List<UUID>): List<TodoView> =
        todoRepo.findTodosByIds(ctx.repoCtx, ctx.mustGetAppId(), ids)

    @Transactional
    fun updateByIds(ctx: OperationContext, inputs: List<TodoUpdateInput>): Int {
        if (inputs.isEmpty()) return 0
        return todoRepo.batchUpdate(ctx.repoCtx, ctx.mustGetAppId(), inputs).size
    }

    @Transactional
    fun deleteByIds(ctx: OperationContext, ids: List<UUID>): Int =
        todoRepo.deleteTodosByIds(ctx.repoCtx, ctx.mustGetAppId(), ids)

    @Transactional
    fun deleteItems(ctx: OperationContext, itemIds: List<UUID>): Int =
        todoRepo.deleteItemsByIds(ctx.repoCtx, ctx.mustGetAppId(), itemIds)
}
