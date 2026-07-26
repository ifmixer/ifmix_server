package com.ifmix.api.core.modules.todo

/** 文档 → 响应 DTO 映射（含 Instant → epoch 毫秒）。 */
object TodoMapper {

    fun toResponse(doc: TodoDocument): TodoResponse =
        TodoResponse(
            id = doc.id,
            title = doc.title,
            done = doc.done,
            items = doc.items.map { TodoItemResponse(it.id, it.content, it.done) },
            createdAt = doc.createdAt?.toEpochMilli() ?: 0L,
            updatedAt = doc.updatedAt?.toEpochMilli() ?: 0L,
        )
}
