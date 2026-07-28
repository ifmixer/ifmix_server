package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.TodoService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** customer BFF 的 todo 路由。暂使用旧式类型，待 Todo 模块完全迁移后再更新。 */
@RestController
@RequestMapping("/customer/core")
class CustomerTodoController(private val todoService: TodoService) {

    @PutMapping("/query/todo/findByCursor")
    fun findByCursor(
        ctx: RequestContext,
        @RequestBody(required = false) input: CursorQueryInput?,
        // TODO: Implement with Jimmer pagination
    ): Any = TODO("Not implemented")

    @PutMapping("/query/todo/getById")
    fun getById(ctx: RequestContext, /* TODO: ByIdRequest */): Any = TODO("Not implemented")

    @PostMapping("/mutation/todo/createOne")
    fun createOne(ctx: RequestContext, /* TODO: CreateTodoRequest */): Any = TODO("Not implemented")

    @PostMapping("/mutation/todo/updateOne")
    fun updateOne(ctx: RequestContext, /* TODO: UpdateOneTodoRequest */): Unit = TODO("Not implemented")

    @PostMapping("/mutation/todo/deleteById")
    fun deleteById(ctx: RequestContext, /* TODO: ByIdRequest */): Any = TODO("Not implemented")
}
