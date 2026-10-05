package com.ifmix.core.api.bff.rpc.customer.demo

import com.ifmix.core.api.dto.common.ActionResult
import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.dto.demo.CreateTodoInputDto
import com.ifmix.core.api.dto.demo.CreateTodoResultDto
import com.ifmix.core.api.dto.demo.DemoRpcMappers
import com.ifmix.core.api.dto.demo.FindTodoByIdInput
import com.ifmix.core.api.dto.demo.FindTodosByIdsInput
import com.ifmix.core.api.dto.demo.FindTodosInput
import com.ifmix.core.api.dto.demo.TodoDto
import com.ifmix.core.api.dto.demo.UpdateTodoInputDto
import com.ifmix.core.api.dto.demo.UpdateTodoItemsMutationInputDto
import com.ifmix.core.api.dto.demo.UpdateTodoResultDto
import com.ifmix.core.api.dto.demo.UpdateTodoItemsResultDto
import com.ifmix.core.api.infra.http.ActionContextFactory
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.Envelope
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.RpcRequestBody
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
import tools.jackson.databind.ObjectMapper

/**
 * demo 模块 RPC 试点 controller（proposal §5.2）：8 个 `POST /rpc/customer/core/{actionName}`
 *（proposal §一：`/rpc/` 前缀，CF/WAF 按 `/rpc/customer/` 路径前缀一条规则覆盖；actionName 四段
 * `{q|m}_{module}_{resource}_{action}`，与客户端 rpcDemo.ts 的 8 个 action 一一对应）。
 *
 * 请求体为 S2 [RpcRequestBody]（`{meta, input}` 信封，wire v3 解密后到达；缺 input 段按空
 * node 处理——Jackson 3 无 EmptyNode，等价空 node 为 [MissingNode]）；`input` 段经
 * [objectMapper].convertValue 转各 action 的协议 DTO（dto/demo），缺必填字段 → Jackson 报错
 *（由 Spring 边界映射为 400000 语义）。
 *
 * 统一模式：[ActionContextFactory.fromRpc] 构造 ctx（真实实例，JWT 走 meta.accessToken）→
 * mutation 用 [GlobalTxRunner.withTx] 包 Facade 调用（写后读一律用**原 ctx**，对齐 DemoFetcher
 * 语义），query 直调 [DemoQueryService]（聚合层，禁 N+1）→ 返回 [Envelope]。
 *
 * springdoc 标注仅顺手（operationId = reqName）；全量 OpenAPI 契约是阶段 6 的事（§八「明确不做」）。
 */
@RestController
@RequestMapping("/rpc/customer/core", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Demo RPC", description = "demo 模块 RPC 试点（GraphQL 去化第一阶段，§八：试点通过 review 前不迁其他模块）")
class DemoController(
    private val ctxFactory: ActionContextFactory,
    private val facade: DemoFacade,
    private val queryService: DemoQueryService,
    private val globalTx: GlobalTxRunner,
    private val objectMapper: ObjectMapper,
) {

    /** 缺 input 段 → 空 object node（非 MissingNode：MissingNode convertValue 得 null 会 NPE；
     *  空 node 让必填字段缺失走 Jackson InvalidNullException → 400 语义）；非法 input 由 Spring 边界映射 400000。 */
    private fun <T> input(body: RpcRequestBody, clazz: Class<T>): T =
        objectMapper.convertValue(body.input ?: objectMapper.createObjectNode(), clazz)

    @Operation(operationId = "q_demo_todo_getById")
    @PostMapping("q_demo_todo_getById", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findTodoById(request: HttpServletRequest, @RequestBody body: RpcRequestBody): ResponseEntity<Envelope<TodoDto>> {
        val ctx = ctxFactory.fromRpc(request, DemoSpecs.FIND_TODO_BY_ID, body.meta)
        val input = input(body, FindTodoByIdInput::class.java)
        val todo = queryService.findTodoById(ctx, input.id)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "Todo not found: ${input.id}")
        return ResponseEntity.ok(Envelope.ok(todo))
    }

    @Operation(operationId = "q_demo_todo_getByIds")
    @PostMapping("q_demo_todo_getByIds", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findTodosByIds(request: HttpServletRequest, @RequestBody body: RpcRequestBody): ResponseEntity<Envelope<List<TodoDto>>> {
        val ctx = ctxFactory.fromRpc(request, DemoSpecs.FIND_TODOS_BY_IDS, body.meta)
        val input = input(body, FindTodosByIdsInput::class.java)
        return ResponseEntity.ok(Envelope.ok(queryService.findTodosByIds(ctx, input.ids)))
    }

    @Operation(operationId = "q_demo_todo_list")
    @PostMapping("q_demo_todo_list", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findTodos(request: HttpServletRequest, @RequestBody body: RpcRequestBody): ResponseEntity<Envelope<Page<TodoDto>>> {
        val ctx = ctxFactory.fromRpc(request, DemoSpecs.FIND_TODOS, body.meta)
        val input = input(body, FindTodosInput::class.java)
        return ResponseEntity.ok(Envelope.ok(queryService.findTodos(ctx, input.findOptions)))
    }

    @Operation(operationId = "m_demo_todo_createOne")
    @PostMapping("m_demo_todo_createOne", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createTodo(request: HttpServletRequest, @RequestBody body: RpcRequestBody): ResponseEntity<Envelope<CreateTodoResultDto>> {
        val ctx = ctxFactory.fromRpc(request, DemoSpecs.CREATE_TODO, body.meta)
        val input = input(body, CreateTodoInputDto::class.java)
        val created = globalTx.withTx(ctx) { txCtx -> facade.create(txCtx, DemoRpcMappers.toGenerated(input)) }
        // 复用批量组装逻辑（单元素列表）补全 items + counts 完整视图
        val full = queryService.findTodosByIds(ctx, listOf(created.id)).singleOrNull()
            ?: throw ApiError(ErrorCode.INTERNAL, "todo not found after create: ${created.id}")
        return ResponseEntity.ok(Envelope.ok(CreateTodoResultDto(todo = full)))
    }

    @Operation(operationId = "m_demo_todo_updateOne")
    @PostMapping("m_demo_todo_updateOne", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun updateTodo(request: HttpServletRequest, @RequestBody body: RpcRequestBody): ResponseEntity<Envelope<UpdateTodoResultDto>> {
        val ctx = ctxFactory.fromRpc(request, DemoSpecs.UPDATE_TODO, body.meta)
        val input = input(body, UpdateTodoInputDto::class.java)
        globalTx.withTx(ctx) { txCtx -> facade.partialUpdate(txCtx, DemoRpcMappers.toGenerated(input)) }
        // 写后读用**原 ctx**（非 txCtx）——对齐 DemoFetcher.updateTodo:57 的写后读语义
        val todo = queryService.findTodoById(ctx, input.id)
        return ResponseEntity.ok(Envelope.ok(UpdateTodoResultDto(success = true, todo = todo)))
    }

    @Operation(operationId = "m_demo_todo_updateItems")
    @PostMapping("m_demo_todo_updateItems", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun batchUpdateTodoItems(request: HttpServletRequest, @RequestBody body: RpcRequestBody): ResponseEntity<Envelope<UpdateTodoItemsResultDto>> {
        val ctx = ctxFactory.fromRpc(request, DemoSpecs.BATCH_UPDATE_TODO_ITEMS, body.meta)
        val input = input(body, UpdateTodoItemsMutationInputDto::class.java)
        globalTx.withTx(ctx) { txCtx -> facade.batchUpdateItems(txCtx, DemoRpcMappers.toGenerated(input)) }
        return ResponseEntity.ok(Envelope.ok(UpdateTodoItemsResultDto(success = true)))
    }

    @Operation(operationId = "m_demo_todo_deleteOne")
    @PostMapping("m_demo_todo_deleteOne", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deleteTodo(request: HttpServletRequest, @RequestBody body: RpcRequestBody): ResponseEntity<Envelope<ActionResult>> {
        val ctx = ctxFactory.fromRpc(request, DemoSpecs.DELETE_TODO, body.meta)
        val input = input(body, FindTodoByIdInput::class.java)
        globalTx.withTx(ctx) { txCtx -> facade.deleteById(txCtx, input.id) }
        return ResponseEntity.ok(Envelope.ok(ActionResult(success = true)))
    }

    @Operation(operationId = "m_demo_todo_deleteMany")
    @PostMapping("m_demo_todo_deleteMany", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deleteTodoByIds(request: HttpServletRequest, @RequestBody body: RpcRequestBody): ResponseEntity<Envelope<ActionResult>> {
        val ctx = ctxFactory.fromRpc(request, DemoSpecs.DELETE_TODO_BY_IDS, body.meta)
        val input = input(body, FindTodosByIdsInput::class.java)
        val count = globalTx.withTx(ctx) { txCtx -> facade.deleteByIds(txCtx, input.ids) }
        return ResponseEntity.ok(Envelope.ok(ActionResult(success = true, modifiedCount = count)))
    }
}
