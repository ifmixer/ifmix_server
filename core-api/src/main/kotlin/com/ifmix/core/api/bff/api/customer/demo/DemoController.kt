package com.ifmix.core.api.bff.api.customer.demo

import com.ifmix.core.api.dto.common.ActionResult
import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.dto.demo.CreateTodoInput
import com.ifmix.core.api.dto.demo.CreateTodoRes
import com.ifmix.core.api.dto.demo.DemoApiMappers
import com.ifmix.core.api.dto.demo.FindTodoByIdInput
import com.ifmix.core.api.dto.demo.FindTodosByIdsInput
import com.ifmix.core.api.dto.demo.FindTodosInput
import com.ifmix.core.api.dto.demo.TodoRes
import com.ifmix.core.api.dto.demo.UpdateTodoInput
import com.ifmix.core.api.dto.demo.UpdateTodoItemsMutationInput
import com.ifmix.core.api.dto.demo.UpdateTodoRes
import com.ifmix.core.api.dto.demo.UpdateTodoItemsRes
import com.ifmix.core.api.infra.http.ActionContextFactory
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.Envelope
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.ApiRequestBody
import com.ifmix.core.api.infra.http.requireInput
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.demo.DemoFacade
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * demo 模块 API 试点 controller（rollout §1/§3.3）：8 个 `POST /api/customer/core/{actionName}`
 *（`/api/` 前缀替代旧 `/rpc/`；actionName 四段 `{q|m}_{module}_{resource}_{action}`，与客户端的
 * 8 个 action 一一对应，总表见 rpc-rollout-client.md §1.1）。
 *
 * 请求体为 [ApiRequestBody]（`{meta, input}` 信封，wire 解密后到达）；input 类型由各 endpoint
 * 签名泛型声明，Spring 边界反序列化（缺必填字段 → HttpMessageNotReadableException → 400000），
 * 必填 input 用 [requireInput] 收口。
 *
 * 统一模式：[ActionContextFactory.fromRpc] 构造 ctx（真实实例，JWT 走 meta.accessToken）→
 * mutation 用 [GlobalTxRunner.withTx] 包 Facade 调用（写后读一律用**原 ctx**，对齐 DemoFetcher
 * 语义），query 直调 [DemoQueryService]（聚合层，禁 N+1）→ 返回 [Envelope]（`.copy(reqId=ctx.requestId)` 回显）。
 *
 * springdoc 标注仅顺手（operationId = actionName）；全量 OpenAPI 契约是 M4 的事（rollout §6「明确不做」）。
 */
@RestController
@RequestMapping("/api/customer/core", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Demo API", description = "demo 模块 API 试点（GraphQL 去化第一阶段，试点通过 review 前不迁其他模块）")
class DemoController(
    private val ctxFactory: ActionContextFactory,
    private val facade: DemoFacade,
    private val queryService: DemoQueryService,
    private val globalTx: GlobalTxRunner,
) {

    @Operation(operationId = "q_demo_todo_getById")
    @PostMapping("q_demo_todo_getById", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findTodoById(request: HttpServletRequest, @RequestBody body: ApiRequestBody<FindTodoByIdInput>): ResponseEntity<Envelope<TodoRes>> {
        val ctx = ctxFactory.fromRpc(request, DemoSpecs.FIND_TODO_BY_ID, body.meta)
        val input = body.requireInput()
        val todo = queryService.findTodoById(ctx, input.id)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "Todo not found: ${input.id}")
        return ResponseEntity.ok(Envelope.ok(todo).copy(reqId = ctx.requestId))
    }

    @Operation(operationId = "q_demo_todo_getByIds")
    @PostMapping("q_demo_todo_getByIds", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findTodosByIds(request: HttpServletRequest, @RequestBody body: ApiRequestBody<FindTodosByIdsInput>): ResponseEntity<Envelope<List<TodoRes>>> {
        val ctx = ctxFactory.fromRpc(request, DemoSpecs.FIND_TODOS_BY_IDS, body.meta)
        val input = body.requireInput()
        return ResponseEntity.ok(Envelope.ok(queryService.findTodosByIds(ctx, input.ids)).copy(reqId = ctx.requestId))
    }

    @Operation(operationId = "q_demo_todo_list")
    @PostMapping("q_demo_todo_list", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findTodos(request: HttpServletRequest, @RequestBody body: ApiRequestBody<FindTodosInput>): ResponseEntity<Envelope<Page<TodoRes>>> {
        val ctx = ctxFactory.fromRpc(request, DemoSpecs.FIND_TODOS, body.meta)
        val input = body.requireInput()
        return ResponseEntity.ok(Envelope.ok(queryService.findTodos(ctx, input.findOptions)).copy(reqId = ctx.requestId))
    }

    @Operation(operationId = "m_demo_todo_createOne")
    @PostMapping("m_demo_todo_createOne", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createTodo(request: HttpServletRequest, @RequestBody body: ApiRequestBody<CreateTodoInput>): ResponseEntity<Envelope<CreateTodoRes>> {
        val ctx = ctxFactory.fromRpc(request, DemoSpecs.CREATE_TODO, body.meta)
        val input = body.requireInput()
        val created = globalTx.withTx(ctx) { txCtx -> facade.create(txCtx, input) }
        // 复用批量组装逻辑（单元素列表）补全 items + counts 完整视图
        val full = queryService.findTodosByIds(ctx, listOf(created.id)).singleOrNull()
            ?: throw ApiError(ErrorCode.INTERNAL, "todo not found after create: ${created.id}")
        return ResponseEntity.ok(Envelope.ok(CreateTodoRes(todo = full)).copy(reqId = ctx.requestId))
    }

    @Operation(operationId = "m_demo_todo_updateOne")
    @PostMapping("m_demo_todo_updateOne", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun updateTodo(request: HttpServletRequest, @RequestBody body: ApiRequestBody<UpdateTodoInput>): ResponseEntity<Envelope<UpdateTodoRes>> {
        val ctx = ctxFactory.fromRpc(request, DemoSpecs.UPDATE_TODO, body.meta)
        val input = body.requireInput()
        globalTx.withTx(ctx) { txCtx -> facade.partialUpdate(txCtx, input) }
        // 写后读用**原 ctx**（非 txCtx）——对齐 DemoFetcher.updateTodo:57 的写后读语义
        val todo = queryService.findTodoById(ctx, input.id)
        return ResponseEntity.ok(Envelope.ok(UpdateTodoRes(success = true, todo = todo)).copy(reqId = ctx.requestId))
    }

    @Operation(operationId = "m_demo_todo_updateItems")
    @PostMapping("m_demo_todo_updateItems", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun batchUpdateTodoItems(request: HttpServletRequest, @RequestBody body: ApiRequestBody<UpdateTodoItemsMutationInput>): ResponseEntity<Envelope<UpdateTodoItemsRes>> {
        val ctx = ctxFactory.fromRpc(request, DemoSpecs.BATCH_UPDATE_TODO_ITEMS, body.meta)
        val input = body.requireInput()
        globalTx.withTx(ctx) { txCtx -> facade.batchUpdateItems(txCtx, input) }
        return ResponseEntity.ok(Envelope.ok(UpdateTodoItemsRes(success = true)).copy(reqId = ctx.requestId))
    }

    @Operation(operationId = "m_demo_todo_deleteOne")
    @PostMapping("m_demo_todo_deleteOne", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deleteTodo(request: HttpServletRequest, @RequestBody body: ApiRequestBody<FindTodoByIdInput>): ResponseEntity<Envelope<ActionResult>> {
        val ctx = ctxFactory.fromRpc(request, DemoSpecs.DELETE_TODO, body.meta)
        val input = body.requireInput()
        globalTx.withTx(ctx) { txCtx -> facade.deleteById(txCtx, input.id) }
        return ResponseEntity.ok(Envelope.ok(ActionResult(success = true)).copy(reqId = ctx.requestId))
    }

    @Operation(operationId = "m_demo_todo_deleteMany")
    @PostMapping("m_demo_todo_deleteMany", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deleteTodoByIds(request: HttpServletRequest, @RequestBody body: ApiRequestBody<FindTodosByIdsInput>): ResponseEntity<Envelope<ActionResult>> {
        val ctx = ctxFactory.fromRpc(request, DemoSpecs.DELETE_TODO_BY_IDS, body.meta)
        val input = body.requireInput()
        val count = globalTx.withTx(ctx) { txCtx -> facade.deleteByIds(txCtx, input.ids) }
        return ResponseEntity.ok(Envelope.ok(ActionResult(success = true, modifiedCount = count)).copy(reqId = ctx.requestId))
    }
}
