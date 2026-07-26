package com.ifmix.api.core.modules.todo

import io.mcarle.konvert.api.GeneratedKonverter

public object TodoMapperImpl : TodoMapper {
  @GeneratedKonverter(priority = 5_000)
  override fun toDto(item: TodoItem): TodoItemDto = TodoItemDto(
    id = item.id,
    content = item.content,
    done = item.done
  )

  @GeneratedKonverter(priority = 5_000)
  override fun toDto(todo: TodoDocument): TodoDto = TodoDto(
    id = todo.id,
    title = todo.title,
    done = todo.done,
    items = todo.items.map { this.toDto(item = it) },
    createdAt = todo.createdAt,
    updatedAt = todo.updatedAt
  )
}
