package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.infra.types.ByIdRequest
import com.ifmix.api.core.infra.types.ByIdsRequest
import com.ifmix.api.core.infra.types.OperationResult
import com.ifmix.api.core.entity.todo.dto.TodoCreateInput
import com.ifmix.api.core.entity.todo.dto.TodoUpdateInput
import com.ifmix.api.core.entity.todo.dto.TodoView
import com.ifmix.api.core.infra.db.CursorQueryInput
import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.db.ownsRow
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.mustGetInstallId
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.todo.service.TodoService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * customer BFF 的 todo 路由。PUT=query，POST=mutation。
 *
 * 权限模型：
 * - 所有操作要求 installId（匿名/登录都有）
 * - 列表按 appId 返回（父类 findViewByCursor 已按 appId 过滤）
 * - 单条操作先查后校验归属（ownsRow）
 */
@RestController
@RequestMapping("/customer/core")
class CustomerTodoController(private val todoService: TodoService) {

    @PutMapping("/query/todo/findByCursor")
    fun findByCursor(
        ctx: OperationContext,
        @RequestBody(required = false) input: CursorQueryInput?,
    ): Page<TodoView> {
        ctx.mustGetInstallId()
        return todoService.findTodoByCursor(ctx, input ?: CursorQueryInput())
    }

    @PutMapping("/query/todo/getById")
    fun getById(ctx: OperationContext, @Valid @RequestBody req: ByIdRequest): TodoView {
        ctx.mustGetInstallId()
        val todo = todoService.getTodo(ctx, req.id)
        checkOwnership(ctx, todo)
        return todo
    }

    @PostMapping("/mutation/todo/createOne")
    fun createOne(ctx: OperationContext, @Valid @RequestBody req: TodoCreateInput): TodoView {
        ctx.mustGetInstallId()
        return todoService.createOne(ctx, req)
    }

    @PostMapping("/mutation/todo/updateOne")
    fun updateOne(ctx: OperationContext, @Valid @RequestBody req: TodoUpdateInput): OperationResult {
        ctx.mustGetInstallId()
        val existing = todoService.getTodo(ctx, req.id)
        checkOwnership(ctx, existing)
        val updated = todoService.updateOne(ctx, req)
        return OperationResult(success = updated, modifiedCount = if (updated) 1 else 0)
    }

    @PostMapping("/mutation/todo/deleteById")
    fun deleteById(ctx: OperationContext, @Valid @RequestBody req: ByIdRequest): OperationResult {
        ctx.mustGetInstallId()
        val existing = todoService.getTodo(ctx, req.id)
        checkOwnership(ctx, existing)
        val deleted = todoService.deleteOne(ctx, req.id)
        return OperationResult(success = deleted)
    }

    @PostMapping("/mutation/todo/deleteItemsByIds")
    fun deleteItemsByIds(ctx: OperationContext, @Valid @RequestBody req: ByIdsRequest): OperationResult {
        ctx.mustGetInstallId()
        // TODO: 批量 item 归属校验待完善（需通过 item → todo 查归属）
        val count = todoService.deleteItems(ctx, req.ids)
        return OperationResult(success = count == req.ids.size, modifiedCount = count)
    }

    @PutMapping("/query/todo/getByIds")
    fun getByIds(ctx: OperationContext, @Valid @RequestBody req: ByIdsRequest): List<TodoView> {
        ctx.mustGetInstallId()
        val todos = todoService.getByIds(ctx, req.ids)
        return todos.filter { ownsRow(ctx, it.userId, it.installId) }
    }

    @PostMapping("/mutation/todo/updateByIds")
    fun updateByIds(ctx: OperationContext, @Valid @RequestBody req: List<TodoUpdateInput>): OperationResult {
        ctx.mustGetInstallId()
        val ids = req.map { it.id }
        val todos = todoService.getByIds(ctx, ids)
        todos.forEach { checkOwnership(ctx, it) }
        val count = todoService.updateByIds(ctx, req)
        return OperationResult(success = count == req.size, modifiedCount = count)
    }

    @PostMapping("/mutation/todo/deleteByIds")
    fun deleteByIds(ctx: OperationContext, @Valid @RequestBody req: ByIdsRequest): OperationResult {
        ctx.mustGetInstallId()
        val todos = todoService.getByIds(ctx, req.ids)
        todos.forEach { checkOwnership(ctx, it) }
        val count = todoService.deleteByIds(ctx, req.ids)
        return OperationResult(success = count == req.ids.size, modifiedCount = count)
    }

    // ==================== 内部 ====================

    private fun checkOwnership(ctx: OperationContext, todo: TodoView) {
        if (!ownsRow(ctx, todo.userId, todo.installId)) {
            throw ApiError(ErrorCode.NOT_FOUND)
        }
    }
}
