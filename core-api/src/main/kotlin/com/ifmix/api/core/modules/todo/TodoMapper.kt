package com.ifmix.api.core.modules.todo

import io.mcarle.konvert.api.Konvert
import io.mcarle.konvert.api.Konverter
import io.mcarle.konvert.api.Mapping

/**
 * Konvert 编译期生成 document -> DTO 映射。items 列表按元素用 toDto(TodoItem) 自动映射；
 * Instant 时间戳用表达式转 epoch 毫秒。通过 Konverter.get<TodoMapper>() 获取生成的实现。
 */
@Konverter
interface TodoMapper {

    fun toDto(item: TodoItem): TodoItemDto

    @Konvert(
        mappings = [
            Mapping(target = "createdAt", expression = "it.createdAt?.toEpochMilli() ?: 0L"),
            Mapping(target = "updatedAt", expression = "it.updatedAt?.toEpochMilli() ?: 0L"),
        ],
    )
    fun toDto(todo: TodoDocument): TodoDto

    companion object
}
