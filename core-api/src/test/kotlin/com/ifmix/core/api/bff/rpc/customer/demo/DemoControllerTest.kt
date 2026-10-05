package com.ifmix.core.api.bff.rpc.customer.demo

import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.dto.common.PageInfo
import com.ifmix.core.api.dto.demo.TodoDto
import com.ifmix.core.api.dto.demo.TodoItemDto
import com.ifmix.core.api.entity.demo.Todo
import com.ifmix.core.api.entity.demo.TodoItem
import com.ifmix.core.api.generated.types.CreateTodoInput
import com.ifmix.core.api.generated.types.UpdateTodoInput
import com.ifmix.core.api.generated.types.UpdateTodoItemsMutationInput
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.auth.VerifiedToken
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ActionContextFactory
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.GlobalExceptionHandler
import com.ifmix.core.api.infra.http.LogContext
import com.ifmix.core.api.infra.http.RequestMeta
import com.ifmix.core.api.infra.http.RpcRequestBody
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.demo.DemoFacade
import com.ifmix.core.api.entity.common.ActorTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import kotlin.reflect.full.declaredMemberFunctions

/**
 * [DemoController] 单测（proposal §5.3）：仿 InstallFetcherAttestTest 的 Mockito 风格 +
 * S2 [ActionContextFactoryTest] 的 ctxFactory 构造模式（真实 factory + mock JWT + MockHttpServletRequest）。
 *
 * - ctxFactory：**真实** [ActionContextFactory]（JWT mock 返回 customer token）——meta → ctx 走真实解析；
 * - globalTx：**mock**（withTx 直通 lambda，不真开事务；事务边界由 e2e/M0 覆盖，等价 InstallFetcherAttestTest）；
 * - facade / queryService：mock（query 直走聚合层、mutation 经 withTx）。
 */
class DemoControllerTest {

    private val projectId = "ifmix-demo"
    /** factory 解析 meta 后产出的 ctx（query 直用、mutation 写后读用「原 ctx」）。 */
    private val ctx = ActionContext(projectId = projectId)
    private val now = Instant.parse("2026-10-06T00:00:00Z")

    private val jwt = mock<AuthJwtService>()
    private val facade = mock<DemoFacade>()
    private val queryService = mock<DemoQueryService>()
    private val globalTx = mock<GlobalTxRunner>()

    private lateinit var objectMapper: ObjectMapper
    private lateinit var controller: DemoController

    @BeforeEach
    fun setUp() {
        objectMapper = JsonMapper.builder().build()
        val ctxFactory = ActionContextFactory(jwt, strict = true)
        whenever(jwt.verify(any())).thenReturn(
            VerifiedToken(
                actorId = UUID.randomUUID().toString(),
                projectId = projectId,
                actorType = ActorTypes.CUSTOMER,
                tokenType = AuthJwtService.TOKEN_TYPE_CUSTOMER,
            ),
        )
        // mutation 包 withTx：mock 直通 lambda（不真开事务）；query 路径不触 globalTx。
        whenever(globalTx.withTx<Any>(any(), any())).thenAnswer {
            ((it.arguments[1]) as (ActionContext) -> Any)(ctx)
        }
        controller = DemoController(ctxFactory, facade, queryService, globalTx, objectMapper)
    }

    // ===== 公共构造 =====

    /** customer token + projectId 的合法 meta。 */
    private fun validMeta() = RequestMeta(projectId = projectId, accessToken = "SECRET-customer")

    /** 用 mapper 把 meta/input 段组装成 controller 收到的 [RpcRequestBody]（wire 形状）；input 空 → null 段。 */
    private fun body(meta: RequestMeta?, input: Map<String, Any?>): RpcRequestBody {
        val node = if (input.isEmpty()) null
        else objectMapper.convertValue(input, tools.jackson.databind.node.ObjectNode::class.java)
        return RpcRequestBody(meta, node)
    }

    private fun request() = MockHttpServletRequest().apply { LogContext.start(this) }

    private fun fullTodo(id: UUID, title: String): Todo {
        val pid = projectId
        return Todo {
            this.id = id
            this.projectId = pid
            this.title = title
            done = false; note = null; meta = null; recommend = null; customerId = null
            createdAt = now; updatedAt = now
        }
    }

    private fun pageOf(todos: List<Todo>) = Page(todos, PageInfo(nextCursor = todos.lastOrNull()?.id?.toString()))

    // ===== 1. 每个 endpoint 一条成功用例 =====

    @Test
    fun `findTodoById success returns 200000 envelope and query bypasses tx`() {
        val todo = fullTodo(UUID.randomUUID(), "t")
        whenever(queryService.findTodoById(any(), eq(todo.id))).thenReturn(
            TodoDto(id = todo.id, title = "t", done = false, note = null, meta = null, recommend = null,
                items = emptyList(), itemCount = 0, pendingCount = 0, finishCount = 0,
                createdAt = now.toString(), updatedAt = null),
        )
        val resp = controller.findTodoById(request(), body(validMeta(), mapOf("id" to todo.id.toString())))

        assertEquals("200000", resp.body!!.code)
        assertEquals(todo.id, resp.body!!.data?.id)
        verifyNoInteractions(globalTx)   // query 路径不进 withTx
        verifyNoInteractions(facade)     // query 直走聚合层
        LogContext.clear()
    }

    @Test
    fun `findTodosByIds success`() {
        val a = UUID.randomUUID()
        whenever(queryService.findTodosByIds(any(), eq(listOf(a)))).thenReturn(emptyList())
        val resp = controller.findTodosByIds(request(), body(validMeta(), mapOf("ids" to listOf(a.toString()))))
        assertEquals("200000", resp.body!!.code)
        assertEquals(emptyList<TodoDto>(), resp.body!!.data)
        verifyNoInteractions(globalTx)
        LogContext.clear()
    }

    @Test
    fun `findTodos success passes dto find options to query service`() {
        whenever(queryService.findTodos(any(), anyOrNull())).thenReturn(Page(emptyList<TodoDto>(), PageInfo()))
        // input 显式含 findOptions（此处 null）→ FindTodosInput(findOptions=null)，controller 传 null 给聚合层
        val resp = controller.findTodos(request(), body(validMeta(), mapOf("findOptions" to null)))
        assertEquals("200000", resp.body!!.code)
        assertEquals(emptyList<TodoDto>(), resp.body!!.data?.items)
        verifyNoInteractions(globalTx)
        LogContext.clear()
    }

    @Test
    fun `createTodo wraps facade in tx and assembles full view`() {
        val id = UUID.randomUUID()
        whenever(facade.create(any(), any())).thenReturn(fullTodo(id, "created"))
        whenever(queryService.findTodosByIds(any(), eq(listOf(id)))).thenReturn(
            listOf(
                TodoDto(id = id, title = "created", done = false, note = null, meta = null, recommend = null,
                    items = listOf(TodoItemDto(id = UUID.randomUUID(), content = "i", done = false, note = null,
                        createdAt = now.toString(), updatedAt = null)),
                    itemCount = 1, pendingCount = 1, finishCount = 0,
                    createdAt = now.toString(), updatedAt = now.toString()),
            ),
        )
        val resp = controller.createTodo(request(), body(validMeta(), mapOf("title" to "created")))

        assertEquals("200000", resp.body!!.code)
        assertEquals(id, resp.body!!.data?.todo?.id)
        // mutation 包了 withTx（mock 直通，但仍记录调用）
        verify(globalTx).withTx<Any>(any(), any())
        // input 经 mapper 转 generated CreateTodoInput
        val captor = argumentCaptor<CreateTodoInput>()
        verify(facade).create(any(), captor.capture())
        assertEquals("created", captor.firstValue.title)
        // 写后读用原 ctx（query service 直调）
        verify(queryService).findTodosByIds(any(), eq(listOf(id)))
        LogContext.clear()
    }

    @Test
    fun `updateTodo writes in tx then reads back`() {
        val id = UUID.randomUUID()
        whenever(queryService.findTodoById(any(), eq(id))).thenReturn(
            TodoDto(id = id, title = "updated", done = false, note = null, meta = null, recommend = null,
                items = emptyList(), itemCount = 0, pendingCount = 0, finishCount = 0,
                createdAt = now.toString(), updatedAt = now.toString()),
        )
        val input = mapOf("id" to id.toString(), "set" to mapOf("title" to "updated"))
        val resp = controller.updateTodo(request(), body(validMeta(), input))

        assertEquals("200000", resp.body!!.code)
        assertEquals(true, resp.body!!.data?.success)
        assertEquals(id, resp.body!!.data?.todo?.id)
        verify(globalTx).withTx<Any>(any(), any())
        val captor = argumentCaptor<UpdateTodoInput>()
        verify(facade).partialUpdate(any(), captor.capture())
        // 写后读：query service findTodoById 被调用（controller 用原 ctx，非 tx 的 lambda ctx）
        verify(queryService).findTodoById(any(), eq(id))
        LogContext.clear()
    }

    @Test
    fun `batchUpdateTodoItems success`() {
        val input = mapOf("update" to listOf(mapOf("id" to UUID.randomUUID().toString(), "unset" to listOf("NOTE"))))
        val resp = controller.batchUpdateTodoItems(request(), body(validMeta(), input))
        assertEquals("200000", resp.body!!.code)
        assertEquals(true, resp.body!!.data?.success)
        val captor = argumentCaptor<UpdateTodoItemsMutationInput>()
        verify(facade).batchUpdateItems(any(), captor.capture())
        verify(globalTx).withTx<Any>(any(), any())
        LogContext.clear()
    }

    @Test
    fun `deleteTodo success returns action result`() {
        val id = UUID.randomUUID()
        whenever(facade.deleteById(any(), eq(id))).thenReturn(true)
        val resp = controller.deleteTodo(request(), body(validMeta(), mapOf("id" to id.toString())))
        assertEquals("200000", resp.body!!.code)
        assertEquals(true, resp.body!!.data?.success)
        verify(globalTx).withTx<Any>(any(), any())
        LogContext.clear()
    }

    @Test
    fun `deleteTodoByIds success returns modified count`() {
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID())
        whenever(facade.deleteByIds(any(), eq(ids))).thenReturn(2)
        val resp = controller.deleteTodoByIds(request(), body(validMeta(), mapOf("ids" to ids.map { it.toString() })))
        assertEquals("200000", resp.body!!.code)
        assertEquals(true, resp.body!!.data?.success)
        assertEquals(2, resp.body!!.data?.modifiedCount)
        verify(globalTx).withTx<Any>(any(), any())
        LogContext.clear()
    }

    // ===== 2. findTodoById 未命中 → NOT_FOUND（404000 语义）=====

    @Test
    fun `findTodoById miss throws ApiError NOT_FOUND 404000`() {
        val id = UUID.randomUUID()
        whenever(queryService.findTodoById(any(), eq(id))).thenReturn(null)
        val ex = assertThrows(ApiError::class.java) {
            controller.findTodoById(request(), body(validMeta(), mapOf("id" to id.toString())))
        }
        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode)
        assertEquals("404000", ex.errorCode.externalCode)
        assertEquals("Todo not found: $id", ex.message)
        LogContext.clear()
    }

    // ===== 5.2 统一模式：缺 input 段 → 空 node → 缺必填字段 400 语义 =====

    @Test
    fun `missing input segment fails with invalid request`() {
        val ex = assertThrows(Exception::class.java) {
            controller.findTodoById(request(), RpcRequestBody(validMeta(), null))
        }
        // Jackson 3：FindTodoByIdInput.id 非空但缺失 → InvalidNullException（400 语义，
        // 由 Spring 经 HttpMessageNotReadable 边界映射为 INVALID_REQUEST/400000）
        assertTrue(
            ex is tools.jackson.core.JacksonException || ex.message?.contains("Invalid null") == true,
            "got: ${ex::class.qualifiedName} ${ex.message}",
        )
        LogContext.clear()
    }

    // ===== 4. 转换边界：mapper 抛 ApiError(INVALID_REQUEST) 的 HTTP 映射 =====

    @Test
    fun `mapper invalid request errors map to 400 400000 via global handler`() {
        val handler = GlobalExceptionHandler(exposeErrors = true)
        // a) 非法 unset 字段（UpdateTodoInputDto.unset 非 TodoUnsetField 枚举值）
        val unsetEx = run {
            val input = com.ifmix.core.api.dto.demo.UpdateTodoInputDto(id = UUID.randomUUID(), set = null, unset = listOf("NOT_A_FIELD"))
            try { com.ifmix.core.api.dto.demo.DemoRpcMappers.toGenerated(input); throw AssertionError("expected ApiError") }
            catch (e: ApiError) { e }
        }
        assertEquals(ErrorCode.INVALID_REQUEST, unsetEx.errorCode)

        // b) 非法 FilterOp（dto/common CommonFindOptions.filter → generated 转换）
        val opEx = run {
            val options = com.ifmix.core.api.dto.common.CommonFindOptions(
                filter = com.ifmix.core.api.dto.common.FilterGroup(
                    and = listOf(com.ifmix.core.api.dto.common.FilterExpr(
                        field = com.ifmix.core.api.dto.common.FieldFilter(field = "title", op = "REGEX"))),
                ),
            )
            try { com.ifmix.core.api.dto.demo.DemoRpcMappers.toGenerated(options); throw AssertionError("expected ApiError") }
            catch (e: ApiError) { e }
        }
        assertEquals(ErrorCode.INVALID_REQUEST, opEx.errorCode)

        for (e in listOf(unsetEx, opEx)) {
            val resp = handler.handleApiError(e, request())
            assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
            assertEquals("400000", (resp.body as com.ifmix.core.api.infra.http.Envelope<*>).code)
        }
        LogContext.clear()
    }

    // ===== 命名一致性护栏：8 条路由 ↔ DemoSpecs 一一对应 =====

    @Test
    fun `routes one-to-one with DemoSpecs and mutation prefix matches isMutation`() {
        val specs = setOf(
            com.ifmix.core.api.bff.rpc.customer.demo.DemoSpecs.FIND_TODO_BY_ID,
            com.ifmix.core.api.bff.rpc.customer.demo.DemoSpecs.FIND_TODOS_BY_IDS,
            com.ifmix.core.api.bff.rpc.customer.demo.DemoSpecs.FIND_TODOS,
            com.ifmix.core.api.bff.rpc.customer.demo.DemoSpecs.CREATE_TODO,
            com.ifmix.core.api.bff.rpc.customer.demo.DemoSpecs.UPDATE_TODO,
            com.ifmix.core.api.bff.rpc.customer.demo.DemoSpecs.BATCH_UPDATE_TODO_ITEMS,
            com.ifmix.core.api.bff.rpc.customer.demo.DemoSpecs.DELETE_TODO,
            com.ifmix.core.api.bff.rpc.customer.demo.DemoSpecs.DELETE_TODO_BY_IDS,
        )
        assertEquals(8, specs.size)
        specs.forEach { spec ->
            assertEquals(spec.isMutation, spec.reqName.startsWith("m_"))
            assertEquals("demo", spec.reqName.split("_")[1])
            assertEquals("todo", spec.reqName.split("_")[2])
        }

        val postings = DemoController::class.declaredMemberFunctions
            .filter { it.annotations.any { a -> a is org.springframework.web.bind.annotation.PostMapping } }
        assertEquals(8, postings.size)
        val pathOf = postings.associate { f ->
            val path = f.annotations.filterIsInstance<org.springframework.web.bind.annotation.PostMapping>().single().value.first()
            val specConst = f.annotations.filterIsInstance<io.swagger.v3.oas.annotations.Operation>()
                .single().operationId
            path to specs.single { it.reqName == specConst }
        }
        assertEquals(specs.map { it.reqName }.toSet(), pathOf.values.map { it.reqName }.toSet())
    }

}
