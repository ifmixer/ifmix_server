package com.ifmix.core.api.dto.demo

import com.ifmix.core.api.entity.demo.Todo
import com.ifmix.core.api.entity.demo.TodoItem
import com.ifmix.core.api.entity.demo.TodoRecommend
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [DemoKonvertMappers] 生成实现（DemoKonvertMappersImpl）与原手写 DemoApiMappers 的逐字段等价断言
 *（konvert-rollout-server.md K2 验收 §2.3）：
 * - Instant → String 均为 [Instant.toString] ISO-8601 UTC（内置 InstantToStringConverter）；
 * - nullable 字段 null 透传（updatedAt/note/meta/recItems/title/viewCount）；
 * - Todo → TodoRes 的 items/counts 为占位值（空列表 / 0），由调用方 copy 补齐（§2.1 注意事项 4 回退路径）。
 */
class DemoKonvertMappersTest {

    private val now = Instant.parse("2026-10-06T08:30:00Z")
    private val later = Instant.parse("2026-10-06T09:00:00Z")

    private fun item(
        id: UUID = UUID.randomUUID(),
        content: String = "i1",
        done: Boolean = false,
        note: String? = "n",
    ): TodoItem {
        val projectId = "ifmix-demo"
        return TodoItem {
            this.id = id
            this.projectId = projectId
            this.todoId = UUID.randomUUID()
            this.content = content
            this.done = done
            this.note = note
            this.createdAt = now
            this.updatedAt = later
        }
    }

    private fun recommend(withNulls: Boolean = false) = if (withNulls) {
        TodoRecommend(
            sectionId = UUID.randomUUID(),
            sectionName = "sec",
            viewCount = null,
            recItems = null,
        )
    } else {
        TodoRecommend(
            sectionId = UUID.randomUUID(),
            sectionName = "sec",
            viewCount = 7,
            recItems = listOf(
                TodoRecommend.RecItem(
                    recId = UUID.randomUUID(),
                    title = "rec",
                    priority = 3,
                    createdAt = now,
                    updatedAt = later,
                ),
                TodoRecommend.RecItem(
                    recId = UUID.randomUUID(),
                    title = null,
                    priority = 1,
                    createdAt = now,
                    updatedAt = null,
                ),
            ),
        )
    }

    private fun todo(recommend: TodoRecommend?): Todo {
        val projectId = "ifmix-demo"
        return Todo {
            id = UUID.randomUUID()
            this.projectId = projectId
            title = "t"
            done = true
            note = null
            meta = mapOf("k" to "v")
            this.recommend = recommend
            customerId = null
            createdAt = now
            updatedAt = later
        }
    }

    // ===== TodoItem → TodoItemRes（entity 接口源） =====

    @Test
    fun `todoItem maps all fields and instants to iso strings`() {
        val id = UUID.randomUUID()
        val source = item(id = id, content = "c", done = true, note = "note")

        val res = DemoKonvertMappersImpl.toRes(source)

        assertEquals(id, res.id)
        assertEquals("c", res.content)
        assertEquals(true, res.done)
        assertEquals("note", res.note)
        assertEquals(now.toString(), res.createdAt)
        assertEquals(later.toString(), res.updatedAt)
    }

    @Test
    fun `todoItem null note passes through as null`() {
        val source = item(note = null)

        val res = DemoKonvertMappersImpl.toRes(source)

        assertNull(res.note)
        assertEquals(now.toString(), res.createdAt)
        assertEquals(later.toString(), res.updatedAt)
    }

    // ===== TodoRecommend → TodoRecommendRes =====

    @Test
    fun `recommend maps fields and recItems list element-wise`() {
        val source = recommend()
        val res = DemoKonvertMappersImpl.toRes(source)

        assertEquals(source.sectionId, res.sectionId)
        assertEquals("sec", res.sectionName)
        assertEquals(7, res.viewCount)
        val recItems = res.recItems!!
        assertEquals(2, recItems.size)
        assertEquals(source.recItems!![0].recId, recItems[0].recId)
        assertEquals("rec", recItems[0].title)
        assertEquals(3, recItems[0].priority)
        assertEquals(now.toString(), recItems[0].createdAt)
        assertEquals(later.toString(), recItems[0].updatedAt)
        assertNull(recItems[1].title)
        assertNull(recItems[1].updatedAt)
    }

    @Test
    fun `recommend null viewCount and null recItems pass through as null`() {
        val source = recommend(withNulls = true)

        val res = DemoKonvertMappersImpl.toRes(source)

        assertEquals(source.sectionId, res.sectionId)
        assertNull(res.viewCount)
        assertNull(res.recItems)
    }

    // ===== RecItem → TodoRecItemRes =====

    @Test
    fun `recItem maps all fields`() {
        val recId = UUID.randomUUID()
        val source = TodoRecommend.RecItem(recId = recId, title = "t", priority = 2, createdAt = now, updatedAt = later)

        val res = DemoKonvertMappersImpl.toRes(source)

        assertEquals(recId, res.recId)
        assertEquals("t", res.title)
        assertEquals(2, res.priority)
        assertEquals(now.toString(), res.createdAt)
        assertEquals(later.toString(), res.updatedAt)
    }

    // ===== Todo → TodoRes 单源部分（items/counts 为占位，调用方 copy 补齐） =====

    @Test
    fun `todo maps single-source fields, nested recommend, and placeholder items-counts`() {
        val rec = recommend()
        val source = todo(recommend = rec)

        val res = DemoKonvertMappersImpl.toRes(source)

        assertEquals(source.id, res.id)
        assertEquals("t", res.title)
        assertEquals(true, res.done)
        assertNull(res.note)
        assertEquals(mapOf("k" to "v"), res.meta)
        assertEquals(rec.sectionId, res.recommend?.sectionId)
        assertEquals(rec.recItems?.map { it.recId }, res.recommend?.recItems?.map { it.recId })
        assertEquals(now.toString(), res.createdAt)
        assertEquals(later.toString(), res.updatedAt)
        // 占位值：items/counts 由调用方（DemoQueryService.assemble）查询后 copy 补齐
        assertEquals(emptyList<TodoItemRes>(), res.items)
        assertEquals(0, res.itemCount)
        assertEquals(0, res.pendingCount)
        assertEquals(0, res.finishCount)
    }

    @Test
    fun `todo null recommend passes through as null`() {
        val res = DemoKonvertMappersImpl.toRes(todo(recommend = null))

        assertNull(res.recommend)
        assertNull(res.note)
        assertEquals(now.toString(), res.createdAt)
        assertEquals(later.toString(), res.updatedAt)
    }
}
