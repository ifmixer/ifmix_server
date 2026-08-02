package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.common.types.ByIdRequest
import com.ifmix.api.core.common.types.ByIdsRequest
import com.ifmix.api.core.common.types.OperationResult
import com.ifmix.api.core.entity.todo.dto.TodoCreateInput
import com.ifmix.api.core.entity.todo.dto.TodoUpdateInput
import com.ifmix.api.core.entity.todo.dto.TodoView
import com.ifmix.api.core.infra.db.CursorQueryInput
import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.service.todo.TodoService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * customer BFF 的 todo 路由。PUT=query，POST=mutation。
 *
 * 请求/响应体全部用 Jimmer 生成的 DTO（TodoView / TodoCreateInput / TodoUpdateInput），
 * 这样 OpenAPI 才能给出具体 schema 而不是 `object`。
 */
@RestController
@RequestMapping("/customer/core")
class CustomerTodoController(private val todoService: TodoService) {

    @PutMapping("/query/todo/findByCursor")
    fun findByCursor(
        ctx: OperationContext,
        @RequestBody(required = false) input: CursorQueryInput?,
    ): Page<TodoView> = todoService.findTodoByCursor(ctx, input ?: CursorQueryInput())

    @PutMapping("/query/todo/getById")
    fun getById(ctx: OperationContext, @Valid @RequestBody req: ByIdRequest): TodoView =
        todoService.getTodo(ctx, req.id)

    @PostMapping("/mutation/todo/createOne")
    fun createOne(ctx: OperationContext, @Valid @RequestBody req: TodoCreateInput): TodoView =
        todoService.createOne(ctx, req)

    @PostMapping("/mutation/todo/updateOne")
    fun updateOne(ctx: OperationContext, @Valid @RequestBody req: TodoUpdateInput): TodoView =
        todoService.updateOne(ctx, req)

    @PostMapping("/mutation/todo/deleteById")
    fun deleteById(ctx: OperationContext, @Valid @RequestBody req: ByIdRequest): OperationResult {
        val deleted = todoService.deleteOne(ctx, req.id)
        return OperationResult(success = deleted)
    }

    @PostMapping("/mutation/todo/deleteItemsByIds")
    fun deleteItemsByIds(ctx: OperationContext, @Valid @RequestBody req: ByIdsRequest): OperationResult {
        val count = todoService.deleteItems(ctx, req.ids)
        return OperationResult(success = count > 0)
    }
}
