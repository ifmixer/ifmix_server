package com.ifmix.core.api.bff.api.customer.demo

import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.dto.common.PageInfo
import com.ifmix.core.api.dto.demo.TodoRes
import com.ifmix.core.api.dto.demo.TodoItemRes
import com.ifmix.core.api.entity.demo.Todo
import com.ifmix.core.api.entity.demo.TodoItem
import com.ifmix.core.api.dto.demo.CreateTodoInput
import com.ifmix.core.api.dto.demo.UpdateTodoInput
import com.ifmix.core.api.dto.demo.UpdateTodoItemsMutationInput
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.auth.VerifiedToken
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ActionContextFactory
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.GlobalExceptionHandler
import com.ifmix.core.api.infra.http.LogContext
import com.ifmix.core.api.infra.http.RequestMeta
import com.ifmix.core.api.infra.http.ApiRequestBody
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
    private val reqId = "req-demo-1"
    /** factory 解析 meta 后产出的 ctx（query 直用、mutation 写后读用「原 ctx」）。 */
    private val ctx = ActionContext(projectId = projectId, requestId = reqId)
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
        controller = DemoController(ctxFactory, facade, queryService, globalTx)
    }

    // ===== 公共构造 =====

    /** customer token + projectId 的合法 meta（reqId 显式带上，供 Envelope.reqId 回显断言）。 */
    private fun validMeta() = RequestMeta(reqId = reqId, projectId = projectId, accessToken = "SECRET-customer")

    /** 用 mapper 把 meta/input 段组装成 controller 收到的 [ApiRequestBody]（wire 形状）；input 空表 → null 段。 */
    private inline fun <reified T : Any> body(meta: RequestMeta?, input: Map<String, Any?>): ApiRequestBody<T> {
        val converted = if (input.isEmpty()) null else objectMapper.convertValue(input, T::class.java)
        return ApiRequestBody(meta, converted)
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
            TodoRes(id = todo.id, title = "t", done = false, note = null, meta = null, recommend = null,
                items = emptyList(), itemCount = 0, pendingCount = 0, finishCount = 0,
                createdAt = now.toString(), updatedAt = null),
        )
        val resp = controller.findTodoById(request(), body(validMeta(), mapOf("id" to todo.id.toString())))

        assertEquals("200000", resp.body!!.code)
        assertEquals(todo.id, resp.body!!.data?.id)
        assertEquals(reqId, resp.body!!.reqId, "Envelope.reqId must echo meta.reqId on success")
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
        assertEquals(emptyList<TodoRes>(), resp.body!!.data)
        verifyNoInteractions(globalTx)
        LogContext.clear()
    }

    @Test
    fun `findTodos success passes dto find options to query service`() {
        whenever(queryService.findTodos(any(), anyOrNull())).thenReturn(Page(emptyList<TodoRes>(), PageInfo()))
        // input 显式含 findOptions（此处 null）→ FindTodosInput(findOptions=null)，controller 传 null 给聚合层
        val resp = controller.findTodos(request(), body(validMeta(), mapOf("findOptions" to null)))
        assertEquals("200000", resp.body!!.code)
        assertEquals(emptyList<TodoRes>(), resp.body!!.data?.items)
        verifyNoInteractions(globalTx)
        LogContext.clear()
    }

    @Test
    fun `createTodo wraps facade in tx and assembles full view`() {
        val id = UUID.randomUUID()
        whenever(facade.create(any(), any())).thenReturn(fullTodo(id, "created"))
        whenever(queryService.findTodosByIds(any(), eq(listOf(id)))).thenReturn(
            listOf(
                TodoRes(id = id, title = "created", done = false, note = null, meta = null, recommend = null,
                    items = listOf(TodoItemRes(id = UUID.randomUUID(), content = "i", done = false, note = null,
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
        // input 直接反序列化为协议 DTO（CreateTodoInput，generated 已删）
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
            TodoRes(id = id, title = "updated", done = false, note = null, meta = null, recommend = null,
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
        // input 缺段 → requireInput 直接 400000；生产环境非法 shape 在 Spring 反序列化边界
        // 抛 HttpMessageNotReadableException，由 GlobalExceptionHandler 映射同一 400000
        val ex = assertThrows(ApiError::class.java) {
            controller.findTodoById(request(), ApiRequestBody(validMeta(), null))
        }
        assertEquals(ErrorCode.INVALID_REQUEST, ex.errorCode)
        LogContext.clear()
    }

    // ===== 4. 转换边界：协议层纯校验函数抛 ApiError(INVALID_REQUEST) =====

    @Test
    fun `mapper invalid request errors map to 400 400000 via global handler`() {
        val handler = GlobalExceptionHandler(exposeErrors = true)
        // a) 非法 unset 字段（requireUnsetFields 纯校验）
        val unsetEx = run {
            try { com.ifmix.core.api.dto.demo.requireUnsetFields(listOf("NOT_A_FIELD"), com.ifmix.core.api.dto.demo.TODO_UNSET_FIELDS); throw AssertionError("expected ApiError") }
            catch (e: ApiError) { e }
        }
        assertEquals(ErrorCode.INVALID_REQUEST, unsetEx.errorCode)

        // b) 非法 FilterOp（requireFilterOp 纯校验）
        val opEx = run {
            try { com.ifmix.core.api.dto.common.requireFilterOp("REGEX"); throw AssertionError("expected ApiError") }
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

    // ===== 命名一致性护栏：8 条路由四段格式 + module/resource 段 + action 在 rpc-rollout-client §1 表内 =====
    // （实施单 §1.3：原「path ⇔ ActionSpec.isMutation」断言由 factory 运行时前缀 ⇔ 读写校验取代，测试里删掉）

    @Test
    fun `routes one-to-one with controller companion constants`() {
        // rpc-rollout-client.md §1.1 demo 表（8 个 action 名单一真相）
        val expected = setOf(
            "q_demo_todo_getById", "q_demo_todo_getByIds", "q_demo_todo_list",
            "m_demo_todo_createOne", "m_demo_todo_updateOne", "m_demo_todo_updateItems",
            "m_demo_todo_deleteOne", "m_demo_todo_deleteMany",
        )
        val postings = DemoController::class.declaredMemberFunctions
            .filter { it.annotations.any { a -> a is org.springframework.web.bind.annotation.PostMapping } }
        assertEquals(8, postings.size)
        val names = postings.map { f ->
            val path = f.annotations.filterIsInstance<org.springframework.web.bind.annotation.PostMapping>().single().value.first()
            val operationId = f.annotations.filterIsInstance<io.swagger.v3.oas.annotations.Operation>().single().operationId
            assertEquals(path, operationId, "path must equal operationId: $path")
            path
        }
        assertEquals(expected, names.toSet())
        names.forEach { n ->
            val segs = n.split("_")
            assertEquals(4, segs.size, "four-segment format: $n")
            assertTrue(segs[0] in setOf("q", "m"), "q_/m_ prefix: $n")
            assertEquals("demo", segs[1], "module segment: $n")
            assertEquals("todo", segs[2], "resource segment: $n")
        }
        // companion 常量与路由 path 同源
        assertEquals("q_demo_todo_getById", DemoController.REQNAME_FIND_TODO_BY_ID)
    }

}
