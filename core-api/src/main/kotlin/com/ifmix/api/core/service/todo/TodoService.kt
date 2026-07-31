package com.ifmix.api.core.service.todo

import com.ifmix.api.core.entity.todo.Todo
import com.ifmix.api.core.entity.todo.dto.TodoCreateInput
import com.ifmix.api.core.entity.todo.dto.TodoUpdateInput
import com.ifmix.api.core.entity.todo.dto.TodoView
import com.ifmix.api.core.infra.db.CursorQueryInput
import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.RequestContext
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
    fun findViewByCursor(ctx: RequestContext, input: CursorQueryInput): Page<TodoView> =
        todoRepo.findViewByCursorForApp(ctx.appUuid(), input)

    @Transactional(readOnly = true)
    fun getView(ctx: RequestContext, id: UUID): TodoView =
        todoRepo.findViewById(ctx.appUuid(), id) ?: throw ApiError(ErrorCode.NOT_FOUND)

    @Transactional
    fun createOne(ctx: RequestContext, input: TodoCreateInput): TodoView {
        val appId = ctx.appUuid()
        val saved = todoRepo.create(appId, input)
        return todoRepo.findViewById(appId, saved.id) ?: throw ApiError(ErrorCode.INTERNAL)
    }

    @Transactional
    fun updateOne(ctx: RequestContext, input: TodoUpdateInput): TodoView {
        val appId = ctx.appUuid()
        todoRepo.update(appId, input) ?: throw ApiError(ErrorCode.NOT_FOUND)
        return todoRepo.findViewById(appId, input.id) ?: throw ApiError(ErrorCode.NOT_FOUND)
    }

    @Transactional
    fun deleteOne(ctx: RequestContext, id: UUID): Boolean =
        todoRepo.deleteForApp(ctx.appUuid(), id)

    private fun RequestContext.appUuid(): UUID =
        runCatching { UUID.fromString(appId) }.getOrNull()
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-app-id must be a UUID")
}
