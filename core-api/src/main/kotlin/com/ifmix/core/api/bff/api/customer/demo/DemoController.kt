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
 * 语义），query 直调 [DemoQueryService]（聚合层，禁 N+1）→ 返回 [Envelope]（`Envelope.ok(ctx.requestId, data)` 回显 reqId）。
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

    companion object {
        // demo 模块 8 个 RPC action 的 actionName 常量（原 DemoSpecs 常量机械搬移；
        // 全部 CUSTOMER + requireProjectId=true——§3.4 裁决表：demo 是 customer 资源）。
        const val ACTION_FIND_TODO_BY_ID = "q_demo_todo_getById"
        const val ACTION_FIND_TODOS_BY_IDS = "q_demo_todo_getByIds"
        const val ACTION_FIND_TODOS = "q_demo_todo_list"
        const val ACTION_CREATE_TODO = "m_demo_todo_createOne"
        const val ACTION_UPDATE_TODO = "m_demo_todo_updateOne"
        const val ACTION_BATCH_UPDATE_TODO_ITEMS = "m_demo_todo_updateItems"
        const val ACTION_DELETE_TODO = "m_demo_todo_deleteOne"
        const val ACTION_DELETE_TODO_BY_IDS = "m_demo_todo_deleteMany"
    }

    @Operation(operationId = ACTION_FIND_TODO_BY_ID)
    @PostMapping(ACTION_FIND_TODO_BY_ID, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findTodoById(request: HttpServletRequest, @RequestBody body: ApiRequestBody<FindTodoByIdInput>): ResponseEntity<Envelope<TodoRes>> {
        val ctx = ctxFactory.fromRpc(request, ACTION_FIND_TODO_BY_ID, isMutation = false, body = body)
        val input = body.requireInput()
        val todo = queryService.findTodoById(ctx, input.id)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "Todo not found: ${input.id}")
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, todo))
    }

    @Operation(operationId = ACTION_FIND_TODOS_BY_IDS)
    @PostMapping(ACTION_FIND_TODOS_BY_IDS, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findTodosByIds(request: HttpServletRequest, @RequestBody body: ApiRequestBody<FindTodosByIdsInput>): ResponseEntity<Envelope<List<TodoRes>>> {
        val ctx = ctxFactory.fromRpc(request, ACTION_FIND_TODOS_BY_IDS, isMutation = false, body = body)
        val input = body.requireInput()
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, queryService.findTodosByIds(ctx, input.ids)))
    }

    @Operation(operationId = ACTION_FIND_TODOS)
    @PostMapping(ACTION_FIND_TODOS, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findTodos(request: HttpServletRequest, @RequestBody body: ApiRequestBody<FindTodosInput>): ResponseEntity<Envelope<Page<TodoRes>>> {
        val ctx = ctxFactory.fromRpc(request, ACTION_FIND_TODOS, isMutation = false, body = body)
        val input = body.requireInput()
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, queryService.findTodos(ctx, input.findOptions)))
    }

    @Operation(operationId = ACTION_CREATE_TODO)
    @PostMapping(ACTION_CREATE_TODO, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createTodo(request: HttpServletRequest, @RequestBody body: ApiRequestBody<CreateTodoInput>): ResponseEntity<Envelope<CreateTodoRes>> {
        val ctx = ctxFactory.fromRpc(request, ACTION_CREATE_TODO, isMutation = true, body = body)
        val input = body.requireInput()
        val created = globalTx.withTx(ctx) { txCtx -> facade.create(txCtx, input) }
        // 复用批量组装逻辑（单元素列表）补全 items + counts 完整视图
        val full = queryService.findTodosByIds(ctx, listOf(created.id)).singleOrNull()
            ?: throw ApiError(ErrorCode.INTERNAL, "todo not found after create: ${created.id}")
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, CreateTodoRes(todo = full)))
    }

    @Operation(operationId = ACTION_UPDATE_TODO)
    @PostMapping(ACTION_UPDATE_TODO, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun updateTodo(request: HttpServletRequest, @RequestBody body: ApiRequestBody<UpdateTodoInput>): ResponseEntity<Envelope<UpdateTodoRes>> {
        val ctx = ctxFactory.fromRpc(request, ACTION_UPDATE_TODO, isMutation = true, body = body)
        val input = body.requireInput()
        globalTx.withTx(ctx) { txCtx -> facade.partialUpdate(txCtx, input) }
        // 写后读用**原 ctx**（非 txCtx）——对齐 DemoFetcher.updateTodo:57 的写后读语义
        val todo = queryService.findTodoById(ctx, input.id)
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, UpdateTodoRes(success = true, todo = todo)))
    }

    @Operation(operationId = ACTION_BATCH_UPDATE_TODO_ITEMS)
    @PostMapping(ACTION_BATCH_UPDATE_TODO_ITEMS, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun batchUpdateTodoItems(request: HttpServletRequest, @RequestBody body: ApiRequestBody<UpdateTodoItemsMutationInput>): ResponseEntity<Envelope<UpdateTodoItemsRes>> {
        val ctx = ctxFactory.fromRpc(request, ACTION_BATCH_UPDATE_TODO_ITEMS, isMutation = true, body = body)
        val input = body.requireInput()
        globalTx.withTx(ctx) { txCtx -> facade.batchUpdateItems(txCtx, input) }
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, UpdateTodoItemsRes(success = true)))
    }

    @Operation(operationId = ACTION_DELETE_TODO)
    @PostMapping(ACTION_DELETE_TODO, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deleteTodo(request: HttpServletRequest, @RequestBody body: ApiRequestBody<FindTodoByIdInput>): ResponseEntity<Envelope<ActionResult>> {
        val ctx = ctxFactory.fromRpc(request, ACTION_DELETE_TODO, isMutation = true, body = body)
        val input = body.requireInput()
        globalTx.withTx(ctx) { txCtx -> facade.deleteById(txCtx, input.id) }
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, ActionResult(success = true)))
    }

    @Operation(operationId = ACTION_DELETE_TODO_BY_IDS)
    @PostMapping(ACTION_DELETE_TODO_BY_IDS, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deleteTodoByIds(request: HttpServletRequest, @RequestBody body: ApiRequestBody<FindTodosByIdsInput>): ResponseEntity<Envelope<ActionResult>> {
        val ctx = ctxFactory.fromRpc(request, ACTION_DELETE_TODO_BY_IDS, isMutation = true, body = body)
        val input = body.requireInput()
        val count = globalTx.withTx(ctx) { txCtx -> facade.deleteByIds(txCtx, input.ids) }
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, ActionResult(success = true, modifiedCount = count)))
    }
}
