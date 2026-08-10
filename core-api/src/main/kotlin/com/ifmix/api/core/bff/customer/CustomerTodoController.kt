package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.db.ownsRow
import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.ByIdRequest
import com.ifmix.api.core.modules.todo.ByIdsRequest
import com.ifmix.api.core.modules.todo.CreateTodoRequest
import com.ifmix.api.core.modules.todo.DeleteResult
import com.ifmix.api.core.modules.todo.OperationResult
import com.ifmix.api.core.modules.todo.TodoDto
import com.ifmix.api.core.modules.todo.TodoMapper
import com.ifmix.api.core.modules.todo.TodoService
import com.ifmix.api.core.modules.todo.UpdateOneTodoRequest
import com.ifmix.api.core.modules.todo.UpdateTodoWithIdRequest
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
    fun getById(ctx: RequestContext, @Valid @RequestBody req: ByIdRequest): TodoDto {
        if (ctx.installId == null) throw ApiError(ErrorCode.INVALID_REQUEST, "installId required")
        val doc = todoService.getById(ctx, req.id!!)
        if (!ownsRow(ctx, doc.userId, doc.installId)) throw ApiError(ErrorCode.FORBIDDEN)
        return mapper.toDto(doc)
    }

    @PostMapping("/mutation/todo/createOne")
    fun createOne(ctx: RequestContext, @Valid @RequestBody req: CreateTodoRequest): TodoDto {
        val id = todoService.create(ctx, req)
        return mapper.toDto(todoService.getById(ctx, id))
    }

    @PutMapping("/query/core/todo/findTodosByIds")
    fun findByIds(ctx: RequestContext, @Valid @RequestBody req: ByIdsRequest): List<TodoDto> {
        if (ctx.installId == null) throw ApiError(ErrorCode.INVALID_REQUEST, "installId required")
        val docs = todoService.findByIds(ctx, req.ids)
        return docs.filter { ownsRow(ctx, it.userId, it.installId) }.map { mapper.toDto(it) }
    }

    @PostMapping("/mutation/todo/updateOne")
    fun updateOne(ctx: RequestContext, @Valid @RequestBody req: UpdateOneTodoRequest): TodoDto {
        if (ctx.installId == null) throw ApiError(ErrorCode.INVALID_REQUEST, "installId required")
        val doc = todoService.getById(ctx, req.id!!)
        if (!ownsRow(ctx, doc.userId, doc.installId)) throw ApiError(ErrorCode.FORBIDDEN)
        todoService.update(ctx, req.id!!, req.patch!!)
        return mapper.toDto(todoService.getById(ctx, req.id!!))
    }

    @PostMapping("/mutation/core/todo/updateTodosByIds")
    fun updateByIds(ctx: RequestContext, @Valid @RequestBody req: List<UpdateTodoWithIdRequest>): OperationResult {
        if (ctx.installId == null) throw ApiError(ErrorCode.INVALID_REQUEST, "installId required")
        val patches = req.mapNotNull { reqItem -> reqItem.id?.let { id -> id to reqItem.patch!! } }
        val ids = patches.map { it.first }
        // 先校验所有待更新文档的归属权
        val docs = todoService.findByIds(ctx, ids)
        docs.forEach { doc ->
            if (!ownsRow(ctx, doc.userId, doc.installId)) throw ApiError(ErrorCode.FORBIDDEN)
        }
        // 对请求中每条（即使未命中）都计入，确保幂等
        val count = todoService.updateByIds(ctx, patches)
        return OperationResult(success = count == ids.size, modifiedCount = count)
    }

    @PostMapping("/mutation/todo/deleteById")
    fun deleteById(ctx: RequestContext, @Valid @RequestBody req: ByIdRequest): DeleteResult {
        if (ctx.installId == null) throw ApiError(ErrorCode.INVALID_REQUEST, "installId required")
        val doc = todoService.getById(ctx, req.id!!)
        if (!ownsRow(ctx, doc.userId, doc.installId)) throw ApiError(ErrorCode.FORBIDDEN)
        return DeleteResult(todoService.deleteById(ctx, req.id!!))
    }

    @PostMapping("/mutation/core/todo/deleteTodosByIds")
    fun deleteTodosByIds(ctx: RequestContext, @Valid @RequestBody req: ByIdsRequest): OperationResult {
        if (ctx.installId == null) throw ApiError(ErrorCode.INVALID_REQUEST, "installId required")
        val docs = todoService.findByIds(ctx, req.ids)
        docs.forEach { doc ->
            if (!ownsRow(ctx, doc.userId, doc.installId)) throw ApiError(ErrorCode.FORBIDDEN)
        }
        val count = todoService.deleteByIds(ctx, req.ids)
        return OperationResult(success = count == req.ids.size, modifiedCount = count)
    }

    @PostMapping("/mutation/core/todo/deleteTodoItemsByIds")
    fun deleteItemsByIds(ctx: RequestContext, @Valid @RequestBody req: ByIdsRequest): OperationResult {
        if (ctx.installId == null) throw ApiError(ErrorCode.INVALID_REQUEST, "installId required")
        val count = todoService.deleteItemsByIds(ctx, req.ids)
        return OperationResult(success = true, modifiedCount = count)
    }
}

