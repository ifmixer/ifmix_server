package com.ifmix.core.api.dto.demo

import com.ifmix.core.api.entity.demo.Todo
import com.ifmix.core.api.entity.demo.TodoItem
import com.ifmix.core.api.entity.demo.TodoRecommend
import com.ifmix.core.api.modules.demo.TodoItemCounts

/**
 * demo 模块出参视图 mapper（纯 object，无 Spring 依赖）。
 *
 * 入参侧无需 mapper：协议 DTO（[TodoInputs.kt]）由 controller 经 Jackson 直接反序列化，
 * Facade/Handler/Repository 全链路消费手写 DTO（generated types 已全部替换）。
 * DateTime 统一 [Instant.toString] ISO-8601 UTC。
 */
object DemoApiMappers {

    /**
     * Todo entity + 批量 items + counts → [TodoRes]。
     * 聚合在调用方完成（先查根分页、批量查 items/counts 再按 Map 组装，禁止循环 findById）。
     */
    fun toDto(todo: Todo, items: List<TodoItem>, counts: TodoItemCounts): TodoRes =
        TodoRes(
            id = todo.id,
            title = todo.title,
            done = todo.done,
            note = todo.note,
            meta = todo.meta,
            recommend = todo.recommend?.let { recommendToDto(it) },
            items = items.map { toDto(it) },
            itemCount = counts.itemCount,
            pendingCount = counts.pendingCount,
            finishCount = counts.finishCount,
            createdAt = todo.createdAt.toString(),
            updatedAt = todo.updatedAt?.toString(),
        )

    fun toDto(item: TodoItem): TodoItemRes =
        TodoItemRes(
            id = item.id,
            content = item.content,
            done = item.done,
            note = item.note,
            createdAt = item.createdAt.toString(),
            updatedAt = item.updatedAt?.toString(),
        )

    fun recommendToDto(recommend: TodoRecommend): TodoRecommendRes =
        TodoRecommendRes(
            sectionId = recommend.sectionId,
            sectionName = recommend.sectionName,
            viewCount = recommend.viewCount,
            recItems = recommend.recItems?.map { recItemToDto(it) },
        )

    fun recItemToDto(item: TodoRecommend.RecItem): TodoRecItemRes =
        TodoRecItemRes(
            recId = item.recId,
            title = item.title,
            priority = item.priority,
            createdAt = item.createdAt.toString(),
            updatedAt = item.updatedAt?.toString(),
        )
}
