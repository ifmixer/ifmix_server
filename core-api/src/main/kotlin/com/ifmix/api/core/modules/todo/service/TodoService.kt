package com.ifmix.api.core.modules.todo.service

import com.ifmix.api.core.entity.todo.dto.TodoCreateInput
import com.ifmix.api.core.entity.todo.dto.TodoUpdateInput
import com.ifmix.api.core.entity.todo.dto.TodoDetailDto
import com.ifmix.api.core.entity.todo.dto.TodoListDto
import com.ifmix.api.core.infra.dto.CursorQueryInput
import com.ifmix.api.core.infra.dto.Page
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.modules.todo.repo.TodoRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TodoService(
    private val todoRepo: TodoRepository,
) {

    fun findTodoByCursor(ctx: OperationContext, input: CursorQueryInput): Page<TodoListDto> =
        todoRepo.findViewByCursor(ctx.repoCtx, ctx.mustGetAppId(), TodoListDto::class, input)

    fun getTodo(ctx: OperationContext, id: UUID): TodoDetailDto =
        todoRepo.findTodoById(ctx.repoCtx, ctx.mustGetAppId(), id) ?: throw ApiError(ErrorCode.NOT_FOUND)

    @Transactional
    fun createOne(ctx: OperationContext, input: TodoCreateInput): UUID {
        val appId = ctx.mustGetAppId()
        return todoRepo.create(ctx.repoCtx, appId, ctx.installId, ctx.userId, input)
    }

    @Transactional
    fun updateOne(ctx: OperationContext, input: TodoUpdateInput): Boolean {
        return todoRepo.update(ctx.repoCtx, ctx.mustGetAppId(), input)
    }

    @Transactional
    fun deleteOne(ctx: OperationContext, id: UUID): Boolean =
        todoRepo.deleteTodo(ctx.repoCtx, ctx.mustGetAppId(), id)

    fun findByIds(ctx: OperationContext, ids: List<UUID>): List<TodoListDto> =
        todoRepo.findTodoByIds(ctx.repoCtx, ctx.mustGetAppId(), ids)

    @Transactional
    fun updateByIds(ctx: OperationContext, inputs: List<TodoUpdateInput>): Int {
        if (inputs.isEmpty()) return 0
        return todoRepo.batchUpdate(ctx.repoCtx, ctx.mustGetAppId(), inputs)
    }

    @Transactional
    fun deleteByIds(ctx: OperationContext, ids: List<UUID>): Int =
        todoRepo.deleteTodosByIds(ctx.repoCtx, ctx.mustGetAppId(), ids)

    @Transactional
    fun deleteItems(ctx: OperationContext, itemIds: List<UUID>): Int =
        todoRepo.deleteItemsByIds(ctx.repoCtx, ctx.mustGetAppId(), itemIds)
}
