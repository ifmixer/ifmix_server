package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.ByIdRequest
import com.ifmix.api.core.modules.todo.CreateTodoRequest
import com.ifmix.api.core.modules.todo.DeleteResult
import com.ifmix.api.core.modules.todo.TodoDto
import com.ifmix.api.core.modules.todo.TodoMapper
import com.ifmix.api.core.modules.todo.TodoService
import com.ifmix.api.core.modules.todo.UpdateOneTodoRequest
import io.mcarle.konvert.api.Konverter
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

    private val mapper: TodoMapper = Konverter.get()

    @PutMapping("/query/todo/findByCursor")
    fun findByCursor(
        ctx: RequestContext,
        @RequestBody(required = false) input: CursorQueryInput?,
    ): Page<TodoDto> {
        // 租户 appId / 软删 / 分页由 CRUDRepository 强制注入
        val page = todoService.findByCursor(ctx, input ?: CursorQueryInput())
        return Page(page.items.map { mapper.toDto(it) }, page.nextCursor, page.hasMore)
    }

    @PutMapping("/query/todo/getById")
    fun getById(ctx: RequestContext, @Valid @RequestBody req: ByIdRequest): TodoDto =
        mapper.toDto(todoService.getById(ctx, req.id!!))

    @PostMapping("/mutation/todo/createOne")
    fun createOne(ctx: RequestContext, @Valid @RequestBody req: CreateTodoRequest): TodoDto {
        val id = todoService.create(ctx, req)
        return mapper.toDto(todoService.getById(ctx, id))
    }

    @PostMapping("/mutation/todo/updateOne")
    fun updateOne(ctx: RequestContext, @Valid @RequestBody req: UpdateOneTodoRequest): TodoDto {
        todoService.update(ctx, req.id!!, req.patch!!)
        return mapper.toDto(todoService.getById(ctx, req.id))
    }

    @PostMapping("/mutation/todo/deleteById")
    fun deleteById(ctx: RequestContext, @Valid @RequestBody req: ByIdRequest): DeleteResult =
        DeleteResult(todoService.deleteById(ctx, req.id!!))
}
