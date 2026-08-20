package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.generated.mybatis.mapper.*
import com.ifmix.api.core.generated.mybatis.mapper.TodoItemDynamicSqlSupport.appId
import com.ifmix.api.core.generated.mybatis.mapper.TodoItemDynamicSqlSupport.content
import com.ifmix.api.core.generated.mybatis.mapper.TodoItemDynamicSqlSupport.deletedAt
import com.ifmix.api.core.generated.mybatis.mapper.TodoItemDynamicSqlSupport.done
import com.ifmix.api.core.generated.mybatis.mapper.TodoItemDynamicSqlSupport.id
import com.ifmix.api.core.generated.mybatis.mapper.TodoItemDynamicSqlSupport.todoId
import com.ifmix.api.core.generated.mybatis.mapper.TodoItemDynamicSqlSupport.updatedAt
import com.ifmix.api.core.generated.mybatis.model.TodoItem
import com.ifmix.api.core.generated.types.TodoItemUnsetField
import com.ifmix.api.core.generated.types.UpdateTodoItemInput
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class TodoItemRepository(private val mapper: TodoItemMapper) {

    fun insert(entity: TodoItem) = mapper.insert(entity)

    fun deleteByIds(appId_: UUID, ids: Collection<UUID>): Int {
        if (ids.isEmpty()) return 0
        return mapper.update {
            set(deletedAt) equalTo Instant.now()
            where { id.isIn(ids.toList()); appId.isEqualTo(appId_) }
        }
    }

    fun findByTodoIds(appId_: UUID, todoIds: Collection<UUID>): List<TodoItem> {
        if (todoIds.isEmpty()) return emptyList()
        return mapper.select {
            where { appId.isEqualTo(appId_); todoId.isIn(todoIds.toList()); deletedAt.isNull() }
        }
    }

    fun partialUpdate(appId_: UUID, input: UpdateTodoItemInput) {
        val set = input.set
        val unset = input.unset?.toSet() ?: emptySet()
        if (set == null && unset.isEmpty()) return

        mapper.update {
            set?.content?.let { set(content) equalTo it }
            set?.done?.let { set(done) equalTo it }
            set(updatedAt) equalTo Instant.now()
            where { id.isEqualTo(input.id); appId.isEqualTo(appId_) }
        }
    }
}
