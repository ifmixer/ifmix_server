package com.ifmix.core.api.bff.api.customer.demo

import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.dto.common.PageInfo
import com.ifmix.core.api.entity.demo.Todo
import com.ifmix.core.api.entity.demo.TodoItem
import com.ifmix.core.api.dto.common.CommonFindOptions
import com.ifmix.core.api.dto.common.FieldFilter
import com.ifmix.core.api.dto.common.FilterExpr
import com.ifmix.core.api.dto.common.FilterGroup
import com.ifmix.core.api.dto.demo.TodoRes
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.modules.demo.DemoFacade
import com.ifmix.core.api.modules.demo.repo.TodoItemCounts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID

/**
 * [DemoQueryService] 聚合策略单测（brief §5.3-3）：
 * mock [DemoFacade] 三类批量返回（findByIds / findItemsByTodoIds / countItemsByTodoIds），
 * 断言 TodoRes 组装顺序与入参 ids 一致、缺 counts 补 0、缺 items 补空列表、禁循环 findById（无 N+1）。
 */
class DemoQueryServiceTest {

    private val projectId = "ifmix-demo"
    private val ctx = ActionContext(projectId = projectId)
    private val now = Instant.parse("2026-10-06T00:00:00Z")
    private val facade = mock<DemoFacade>()
    private lateinit var service: DemoQueryService

    @BeforeEach
    fun setUp() {
        service = DemoQueryService(facade)
    }

    private fun todo(id: UUID, title: String): Todo {
        val pid = projectId
        return Todo {
            this.id = id
            this.projectId = pid
            this.title = title
            done = false
            note = null
            meta = null
            recommend = null
            customerId = null
            createdAt = now
            updatedAt = now
        }
    }

    private fun item(todoId: UUID, content: String, done: Boolean): TodoItem {
        val pid = projectId
        val id = UUID.randomUUID()
        return TodoItem {
            this.id = id
            this.projectId = pid
            this.todoId = todoId
            this.content = content
            this.done = done
            note = null
            createdAt = now
            updatedAt = now
        }
    }

    // ===== §5.3-3 批量聚合：顺序 = 入参 ids，缺 counts 补 0 =====

    @Test
    fun `findTodosByIds assembles in input id order and fills missing counts with zero`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()
        // 入参 a,b,c；findByIds 乱序返回 [c, a]（b 库中不存在，静默丢弃——对齐 GraphQL findByIds 语义）
        whenever(facade.findByIds(eq(ctx), eq(listOf(a, b, c))))
            .thenReturn(listOf(todo(c, "t3"), todo(a, "t1")))
        // items 乱序 + c 有 1 条、a 有 2 条
        whenever(facade.findItemsByTodoIds(eq(ctx), eq(listOf(c, a))))
            .thenReturn(listOf(item(c, "i3", true), item(a, "i1", false), item(a, "i2", true)))
        // counts 缺 a → 补 0（对齐 TodoItemCountsDataLoader.ZERO 语义）
        whenever(facade.countItemsByTodoIds(eq(ctx), eq(listOf(c, a))))
            .thenReturn(mapOf(c to TodoItemCounts(itemCount = 1, pendingCount = 0, finishCount = 1)))

        val result = service.findTodosByIds(ctx, listOf(a, b, c))

        assertEquals(listOf(a, c), result.map { it.id }, "assembly order must follow input id order")
        val dtoA: TodoRes = result.first()
        assertEquals("t1", dtoA.title)
        assertEquals(listOf("i1", "i2"), dtoA.items.map { it.content })
        assertEquals(0, dtoA.itemCount, "missing counts must be zero-filled")
        assertEquals(0, dtoA.pendingCount)
        assertEquals(0, dtoA.finishCount)
        val dtoC: TodoRes = result[1]
        assertEquals(listOf("i3"), dtoC.items.map { it.content })
        assertEquals(1, dtoC.itemCount)
        assertEquals(1, dtoC.finishCount)
    }

    @Test
    fun `findTodosByIds with empty input issues zero facade queries`() {
        val result = service.findTodosByIds(ctx, emptyList())
        assertTrue(result.isEmpty())
        verify(facade, never()).findByIds(any(), any())
        verify(facade, never()).findItemsByTodoIds(any(), any())
        verify(facade, never()).countItemsByTodoIds(any(), any())
    }

    @Test
    fun `findTodoById hit assembles items and counts with no N+1`() {
        val a = UUID.randomUUID()
        whenever(facade.findById(ctx, a)).thenReturn(todo(a, "t1"))
        whenever(facade.findItemsByTodoIds(eq(ctx), eq(listOf(a))))
            .thenReturn(listOf(item(a, "i1", false), item(a, "i2", true)))
        whenever(facade.countItemsByTodoIds(eq(ctx), eq(listOf(a))))
            .thenReturn(mapOf(a to TodoItemCounts(itemCount = 2, pendingCount = 1, finishCount = 1)))

        val dto = service.findTodoById(ctx, a)

        assertEquals(a, dto?.id)
        assertEquals(listOf("i1", "i2"), dto?.items?.map { it.content })
        assertEquals(2, dto?.itemCount)
        assertEquals(1, dto?.pendingCount)
        assertEquals(1, dto?.finishCount)
        // 无 N+1：1 次根查 + 1 次批量 items + 1 次批量 counts，绝不含循环 findById / findByIds
        verify(facade, times(1)).findById(ctx, a)
        verify(facade, times(1)).findItemsByTodoIds(eq(ctx), eq(listOf(a)))
        verify(facade, times(1)).countItemsByTodoIds(eq(ctx), eq(listOf(a)))
        verify(facade, never()).findByIds(any(), any())
    }

    @Test
    fun `findTodoById miss returns null and skips batch queries`() {
        val a = UUID.randomUUID()
        whenever(facade.findById(ctx, a)).thenReturn(null)

        val dto = service.findTodoById(ctx, a)

        assertNull(dto)
        verify(facade, never()).findItemsByTodoIds(any(), any())
        verify(facade, never()).countItemsByTodoIds(any(), any())
    }

    // ===== 分页聚合 =====

    @Test
    fun `findTodos aggregates page in page order passing dto find options to facade`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val page = Page(listOf(todo(a, "t1"), todo(b, "t2")), PageInfo(nextCursor = b.toString(), hasMore = true))
        whenever(facade.findTodos(eq(ctx), eq(CommonFindOptions(limit = 10, sortDirection = "DESC"))))
            .thenReturn(page)
        whenever(facade.findItemsByTodoIds(eq(ctx), eq(listOf(a, b))))
            .thenReturn(listOf(item(b, "i2", true)))   // a 缺 items → 空列表
        whenever(facade.countItemsByTodoIds(eq(ctx), eq(listOf(a, b))))
            .thenReturn(mapOf(b to TodoItemCounts(1, 0, 1)))   // a 缺 counts → 补 0

        val result = service.findTodos(ctx, CommonFindOptions(limit = 10, sortDirection = "DESC"))

        assertEquals(page.pageInfo, result.pageInfo, "pageInfo must pass through unchanged")
        assertEquals(listOf(a, b), result.items.map { it.id }, "page order must be preserved")
        val dtoA = result.items.first()
        assertTrue(dtoA.items.isEmpty(), "missing items for todo must be empty list")
        assertEquals(0, dtoA.itemCount)
        assertEquals(0, dtoA.pendingCount)
        assertEquals(1, result.items[1].itemCount)
    }

    @Test
    fun `findTodos with null find options passes null to facade`() {
        val a = UUID.randomUUID()
        whenever(facade.findTodos(eq(ctx), isNull())).thenReturn(Page(listOf(todo(a, "t1")), PageInfo()))
        whenever(facade.findItemsByTodoIds(eq(ctx), eq(listOf(a))))
            .thenReturn(emptyList())
        whenever(facade.countItemsByTodoIds(eq(ctx), eq(listOf(a))))
            .thenReturn(emptyMap())

        val result = service.findTodos(ctx, null)

        assertEquals(listOf(a), result.items.map { it.id })
        assertEquals(0, result.items.first().itemCount, "absent counts map entry must zero-fill")
        verify(facade).findTodos(eq(ctx), isNull())    }
}
