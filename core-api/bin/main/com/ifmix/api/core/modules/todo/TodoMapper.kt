package com.ifmix.api.core.modules.todo

import io.mcarle.konvert.api.Konverter

/**
 * Konvert 编译期生成 document -> DTO 映射。items 列表按元素用 toDto(TodoItem) 自动映射；
 * 时间字段 document/DTO 同为 Instant，直接拷贝（对外 JSON 由 Jackson 统一序列化成 epoch 毫秒）。
 * 通过 Konverter.get<TodoMapper>() 获取生成的实现。
 */
@Konverter
interface TodoMapper {

    fun toDto(item: TodoItem): TodoItemDto

    fun toDto(todo: TodoEntity): TodoDto

    companion object
}
