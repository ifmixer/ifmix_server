package com.ifmix.core.api.dto.demo

import com.ifmix.core.api.dto.common.CommonFindOptions
import com.ifmix.core.api.dto.common.FieldFilter
import com.ifmix.core.api.dto.common.FilterExpr
import com.ifmix.core.api.dto.common.FilterGroup
import com.ifmix.core.api.entity.demo.Todo
import com.ifmix.core.api.entity.demo.TodoItem
import com.ifmix.core.api.entity.demo.TodoRecommend
import com.ifmix.core.api.generated.types.CreateTodoItemForTodoInput
import com.ifmix.core.api.generated.types.CreateTodoItemInput
import com.ifmix.core.api.generated.types.CreateTodoInput
import com.ifmix.core.api.generated.types.FilterGroup as GenFilterGroup
import com.ifmix.core.api.generated.types.FilterOp
import com.ifmix.core.api.generated.types.SortDirection
import com.ifmix.core.api.generated.types.TodoItemUnsetField
import com.ifmix.core.api.generated.types.TodoRecItemInput
import com.ifmix.core.api.generated.types.TodoRecommendInput
import com.ifmix.core.api.generated.types.TodoUnsetField
import com.ifmix.core.api.generated.types.UpdateTodoInput
import com.ifmix.core.api.generated.types.UpdateTodoItemsMutationInput
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.modules.demo.repo.TodoItemCounts
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * WP-S3 验收：纯 Kotlin 单测（无 Spring/DGS）。
 * - 非法 unset / op / sortDirection 抛 INVALID_REQUEST
 * - DTO → generated 字段对齐
 * - TodoRecommend 往返（DTO → domain → DTO）字段一致
 */
class DemoRpcMappersTest {

    private val sectionId = UUID.randomUUID()
    private val recId = UUID.randomUUID()
    private val todoId = UUID.randomUUID()
    private val now = Instant.parse("2026-10-06T00:00:00Z")

    private fun recommendInputDto() = TodoRecommendInputDto(
        sectionId = sectionId,
        sectionName = "home",
        viewCount = 42,
        recItems = listOf(
            // §4.4 定稿：客户端不提交 createdAt/updatedAt（服务端 mapper 打戳）
            TodoRecItemInputDto(recId = recId, title = "tip", priority = 1),
        ),
    )

    /** 断言 [block] 抛 [ApiError]（errorCode = [code]，消息包含 [msgPart]），否则 JUnit fail。 */
    private fun assertInvalidRequest(code: ErrorCode, msgPart: String, block: () -> Unit) {
        val e = runCatching { block() }.exceptionOrNull()
        Assertions.assertNotNull(e, "expected ApiError but no exception thrown")
        Assertions.assertTrue(e is ApiError, "expected ApiError but got ${e!!::class}")
        val err = e as ApiError
        Assertions.assertEquals(code, err.errorCode, "unexpected errorCode (msg: ${err.message})")
        Assertions.assertTrue(err.message!!.contains(msgPart), "message '$msgPart' not found in '${err.message}'")
    }

    // ===== 非法值抛 INVALID_REQUEST =====

    @Test
    fun `update todo unset 非法值抛 INVALID_REQUEST`() {
        val input = UpdateTodoInputDto(id = todoId, set = null, unset = listOf("NOTE", "NOT_A_FIELD"))
        assertInvalidRequest(ErrorCode.INVALID_REQUEST, "invalid unset field: NOT_A_FIELD") {
            DemoRpcMappers.toGenerated(input)
        }
    }

    @Test
    fun `update todo item unset 非法值抛 INVALID_REQUEST`() {
        val input = UpdateTodoItemsMutationInputDto(
            update = listOf(UpdateTodoItemInputDto(id = UUID.randomUUID(), set = null, unset = listOf("DONE"))),
        )
        assertInvalidRequest(ErrorCode.INVALID_REQUEST, "invalid unset field: DONE") {
            DemoRpcMappers.toGenerated(input)
        }
    }

    @Test
    fun `filter op 非法值抛 INVALID_REQUEST`() {
        val input = CommonFindOptions(
            filter = FilterGroup(and = listOf(FilterExpr(field = FieldFilter(field = "title", op = "REGEX")))),
        )
        assertInvalidRequest(ErrorCode.INVALID_REQUEST, "invalid filter op: REGEX") {
            DemoRpcMappers.toGenerated(input)
        }
    }

    @Test
    fun `sortDirection 非法值抛 INVALID_REQUEST`() {
        val input = CommonFindOptions(sortDirection = "asc")   // 大小写敏感：小写非法
        assertInvalidRequest(ErrorCode.INVALID_REQUEST, "invalid sortDirection: asc") {
            DemoRpcMappers.toGenerated(input)
        }
    }

    @Test
    fun `recItem 打戳 createdAt 非空且 updatedAt 为 null`() {
        val input = CreateTodoInputDto(
            title = "t",
            recommend = TodoRecommendInputDto(
                sectionId = sectionId,
                sectionName = "home",
                recItems = listOf(TodoRecItemInputDto(recId = recId, priority = 1)),
            ),
        )
        val gen = DemoRpcMappers.toGenerated(input)
        val recItem = gen.recommend!!.recItems!!.single()
        Assertions.assertNotNull(recItem.createdAt, "mapper must stamp createdAt (client omits it)")
        Assertions.assertTrue(recItem.createdAt.isBefore(Instant.now().plusSeconds(5)))
        Assertions.assertNull(recItem.updatedAt, "updatedAt must stay null (server side fills it later)")
    }

    // ===== DTO → generated 字段对齐 =====

    @Test
    fun `createTodo 全字段映射`() {
        val dto = CreateTodoInputDto(
            title = "t",
            done = true,
            note = "n",
            recommend = recommendInputDto(),
            items = listOf(CreateTodoItemInputDto(content = "c", done = false, note = null)),
        )
        val gen = DemoRpcMappers.toGenerated(dto)
        val recItem = gen.recommend!!.recItems!!.single()
        Assertions.assertEquals(recId, recItem.recId)
        Assertions.assertEquals("tip", recItem.title)
        Assertions.assertEquals(1, recItem.priority)
        // §4.4 定稿：createdAt 由 mapper 打戳（非固定 now），updatedAt 保持 null
        Assertions.assertNotNull(recItem.createdAt)
        Assertions.assertNull(recItem.updatedAt)
        Assertions.assertEquals("t", gen.title)
        Assertions.assertEquals(true, gen.done)
        Assertions.assertEquals("n", gen.note)
        Assertions.assertEquals(
            listOf(CreateTodoItemInput(content = "c", done = false, note = null)),
            gen.items,
        )
    }

    @Test
    fun `updateTodo 合法 unset 转枚举`() {
        val dto = UpdateTodoInputDto(id = todoId, set = null, unset = listOf("NOTE", "RECOMMEND"))
        val gen: UpdateTodoInput = DemoRpcMappers.toGenerated(dto)
        Assertions.assertEquals(listOf(TodoUnsetField.NOTE, TodoUnsetField.RECOMMEND), gen.unset)
        Assertions.assertEquals(todoId, gen.id)
        Assertions.assertNull(gen.set)
    }

    @Test
    fun `updateTodoItems 三段映射`() {
        val itemId = UUID.randomUUID()
        val dto = UpdateTodoItemsMutationInputDto(
            create = listOf(CreateTodoItemForTodoInputDto(todoId = todoId, content = "new")),
            update = listOf(UpdateTodoItemInputDto(
                id = itemId,
                set = UpdateTodoItemSetInputDto(content = "x"),
                unset = listOf("NOTE"),
            )),
            delete = listOf(itemId),
        )
        val gen: UpdateTodoItemsMutationInput = DemoRpcMappers.toGenerated(dto)
        Assertions.assertEquals(listOf(CreateTodoItemForTodoInput(todoId = todoId, content = "new")), gen.create)
        Assertions.assertEquals(listOf(TodoItemUnsetField.NOTE), gen.update!!.first().unset)
        Assertions.assertEquals(listOf(itemId), gen.delete)
    }

    @Test
    fun `findOptions 递归 filter + op + sortDirection 映射`() {
        val dto = CommonFindOptions(
            filter = FilterGroup(
                and = listOf(
                    FilterExpr(field = FieldFilter(field = "title", op = "EQ", value = "a")),
                    FilterExpr(
                        group = FilterGroup(
                            or = listOf(FilterExpr(field = FieldFilter(field = "done", op = "IN", values = listOf(true)))),
                        ),
                    ),
                ),
            ),
            cursor = "c",
            sortBy = "id",
            sortDirection = "DESC",
            limit = 10,
        )
        val gen = DemoRpcMappers.toGenerated(dto)
        Assertions.assertEquals("c", gen.cursor)
        Assertions.assertEquals("id", gen.sortBy)
        Assertions.assertEquals(SortDirection.DESC, gen.sortDirection)
        Assertions.assertEquals(10, gen.limit)
        val inner: GenFilterGroup = gen.filter!!
        val and = inner.and!!
        Assertions.assertEquals(FilterOp.EQ, and.first().field!!.op)
        Assertions.assertEquals("a", and.first().field!!.`value`)
        val orGroup = and[1].group!!
        Assertions.assertEquals(FilterOp.IN, orGroup.or!!.first().field!!.op)
        Assertions.assertEquals(listOf<Any>(true), orGroup.or!!.first().field!!.values)
    }

    @Test
    fun `全部合法 op 值可解析`() {
        listOf("EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "NIN", "LIKE", "IS_NULL", "IS_NOT_NULL").forEach { op ->
            val gen = DemoRpcMappers.toGenerated(
                CommonFindOptions(filter = FilterGroup(and = listOf(FilterExpr(field = FieldFilter(field = "title", op = op))))),
            )
            Assertions.assertEquals(FilterOp.valueOf(op), gen.filter!!.and!!.first().field!!.op)
        }
    }

    // ===== TodoRecommend 往返（DTO → domain → DTO）字段一致 =====

    @Test
    fun `recommend 往返字段一致`() {
        val dto = recommendInputDto()
        val domain: TodoRecommend = DemoRpcMappers.recommendToDomain(dto)
        Assertions.assertEquals(sectionId, domain.sectionId)
        Assertions.assertEquals("home", domain.sectionName)
        Assertions.assertEquals(42, domain.viewCount)

        val back: TodoRecommendDto = DemoRpcMappers.recommendToDto(domain)
        Assertions.assertEquals(dto.sectionId, back.sectionId)
        Assertions.assertEquals(dto.sectionName, back.sectionName)
        Assertions.assertEquals(dto.viewCount, back.viewCount)
        val (inRec, outRec) = dto.recItems!!.single() to back.recItems!!.single()
        Assertions.assertEquals(inRec.recId, outRec.recId)
        Assertions.assertEquals(inRec.title, outRec.title)
        Assertions.assertEquals(inRec.priority, outRec.priority)
        // 客户端不提交时间戳：mapper 打戳后往返出的 createdAt 非空、updatedAt 为 null
        Assertions.assertNotNull(outRec.createdAt)
        Assertions.assertNull(outRec.updatedAt)
    }

    // ===== entity → DTO =====

    @Test
    fun `todo entity 转 DTO 字段对齐`() {
        val todo = Todo {
            id = todoId
            this.projectId = "test-app"
            title = "t"
            done = true
            note = "n"
            meta = mapOf("k" to 1)
            recommend = DemoRpcMappers.recommendToDomain(recommendInputDto())
            createdAt = now
            updatedAt = now
        }
        val todoIdLocal = todoId
        val itemId = UUID.randomUUID()
        val item = TodoItem {
            id = itemId
            this.projectId = "test-app"
            this.todoId = todoIdLocal
            content = "c"
            done = false
            note = null
            createdAt = now
            updatedAt = now
        }
        val counts = TodoItemCounts(itemCount = 3, pendingCount = 1, finishCount = 2)
        val dto = DemoRpcMappers.toDto(todo, listOf(item), counts)
        Assertions.assertEquals(todoId, dto.id)
        Assertions.assertEquals("t", dto.title)
        Assertions.assertEquals(true, dto.done)
        Assertions.assertEquals("n", dto.note)
        Assertions.assertEquals(mapOf("k" to 1), dto.meta)
        Assertions.assertEquals(sectionId, dto.recommend!!.sectionId)
        Assertions.assertEquals(1, dto.items.size)
        Assertions.assertEquals(3, dto.itemCount)
        Assertions.assertEquals(1, dto.pendingCount)
        Assertions.assertEquals(2, dto.finishCount)
        Assertions.assertEquals(now.toString(), dto.createdAt)
        Assertions.assertEquals(now.toString(), dto.updatedAt)
        Assertions.assertEquals(item.id, dto.items.first().id)
        Assertions.assertEquals(now.toString(), dto.items.first().createdAt)
        Assertions.assertEquals(now.toString(), dto.items.first().updatedAt)
    }

    @Test
    fun `item DTO 字段对齐`() {
        val todoIdLocal = todoId
        val itemId = UUID.randomUUID()
        val item = TodoItem {
            id = itemId
            projectId = "test-app"
            this.todoId = todoIdLocal
            content = "c"
            done = false
            note = null
            createdAt = now
            updatedAt = now
        }
        val dto = DemoRpcMappers.toDto(item)
        Assertions.assertEquals(item.id, dto.id)
        Assertions.assertEquals("c", dto.content)
        Assertions.assertEquals(false, dto.done)
        Assertions.assertNull(dto.note)
        Assertions.assertEquals(now.toString(), dto.createdAt)
        Assertions.assertEquals(now.toString(), dto.updatedAt)
    }
}
