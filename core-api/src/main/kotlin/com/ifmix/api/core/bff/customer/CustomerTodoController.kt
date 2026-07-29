package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.infra.db.CursorQueryInput
import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.service.todo.TodoService
import org.springframework.web.bind.annotation.*

/** customer BFF 的 todo 路由。PUT=query，POST=mutation。 */
@RestController
@RequestMapping("/customer/core")
class CustomerTodoController(private val todoService: TodoService) {

    @PutMapping("/query/todo/findByCursor")
    fun findByCursor(
        ctx: RequestContext,
        @RequestBody(required = false) input: CursorQueryInput?,
    ): Page<Any> = Page(emptyList(), null, false)

    @PutMapping("/query/todo/getById")
    fun getById(ctx: RequestContext, @RequestBody req: Any): Any = TODO("Not implemented")

    @PostMapping("/mutation/todo/createOne")
    fun createOne(ctx: RequestContext, @RequestBody req: Any): Any = TODO("Not implemented")

    @PostMapping("/mutation/todo/updateOne")
    fun updateOne(ctx: RequestContext, @RequestBody req: Any): Unit = TODO("Not implemented")

    @PostMapping("/mutation/todo/deleteById")
    fun deleteById(ctx: RequestContext, @RequestBody req: Any): Any = TODO("Not implemented")
}
