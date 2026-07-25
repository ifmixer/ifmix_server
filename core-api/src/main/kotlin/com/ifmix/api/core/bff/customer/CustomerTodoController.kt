package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.common.db.CursorQuery
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.db.ReadOptions
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.ByIdRequest
import com.ifmix.api.core.modules.todo.CreateTodoRequest
import com.ifmix.api.core.modules.todo.DeleteResult
import com.ifmix.api.core.modules.todo.TodoMapper
import com.ifmix.api.core.modules.todo.TodoResponse
import com.ifmix.api.core.modules.todo.TodoService
import com.ifmix.api.core.modules.todo.UpdateOneTodoRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** customer BFF 的 todo 路由。PUT=query，POST=mutation。返回值由信封 advice 自动包装。 */
@RestController
@RequestMapping("/customer/core")
class CustomerTodoController(private val todoService: TodoService) {

    @PutMapping("/query/todo/findMany")
    fun findMany(ctx: RequestContext, @RequestBody(required = false) query: CursorQuery?): Page<TodoResponse> {
        val page = todoService.findMany(ctx, query ?: CursorQuery())
        return Page(page.items.map { TodoMapper.toResponse(it) }, page.nextCursor, page.hasMore)
    }

    @PutMapping("/query/todo/getById")
    fun getById(ctx: RequestContext, @Valid @RequestBody req: ByIdRequest): TodoResponse =
        TodoMapper.toResponse(todoService.getById(ctx, req.id!!))

    @PostMapping("/mutation/todo/createOne")
    fun createOne(ctx: RequestContext, @Valid @RequestBody req: CreateTodoRequest): TodoResponse {
        val id = todoService.create(ctx, req)
        return TodoMapper.toResponse(todoService.getById(ctx, id, ReadOptions.PRIMARY))
    }

    @PostMapping("/mutation/todo/updateOne")
    fun updateOne(ctx: RequestContext, @Valid @RequestBody req: UpdateOneTodoRequest): TodoResponse {
        todoService.update(ctx, req.id!!, req.patch!!)
        return TodoMapper.toResponse(todoService.getById(ctx, req.id, ReadOptions.PRIMARY))
    }

    @PostMapping("/mutation/todo/deleteById")
    fun deleteById(ctx: RequestContext, @Valid @RequestBody req: ByIdRequest): DeleteResult =
        DeleteResult(todoService.deleteById(ctx, req.id!!))
}
