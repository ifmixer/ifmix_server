package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoItemDynamicSqlSupport.appId
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoItemDynamicSqlSupport.content
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoItemDynamicSqlSupport.createdAt
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoItemDynamicSqlSupport.deletedAt
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoItemDynamicSqlSupport.done
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoItemDynamicSqlSupport.id
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoItemDynamicSqlSupport.todoId
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoItemDynamicSqlSupport.updatedAt
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoItemMapper
import com.ifmix.api.core.generated.mybatis.mapper.insert
import com.ifmix.api.core.generated.mybatis.mapper.select
import com.ifmix.api.core.generated.mybatis.mapper.selectOne
import com.ifmix.api.core.generated.mybatis.mapper.update
import com.ifmix.api.core.generated.mybatis.model.CoreTodoItem
import com.ifmix.api.core.generated.types.TodoItemUnsetField
import com.ifmix.api.core.generated.types.UpdateTodoItemInput
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * TodoItem 数据访问层。
 */
@Repository
class TodoItemRepository {

    fun findById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID): CoreTodoItem? =
        ctx.mapper<CoreTodoItemMapper>().selectOne {
            where {
                id.isEqualTo(idVal)
                appId.isEqualTo(appIdVal)
                deletedAt.isNull()
            }
        }

    fun findByTodoId(ctx: SvcCtx, todoIdVal: UUID): List<CoreTodoItem> =
        ctx.mapper<CoreTodoItemMapper>().select {
            where {
                todoId.isEqualTo(todoIdVal)
                deletedAt.isNull()
            }
            orderBy(createdAt)
        }

    fun insert(ctx: SvcCtx, entity: CoreTodoItem) {
        ctx.mapper<CoreTodoItemMapper>().insert(entity)
    }

    /**
     * 根据 UpdateTodoItemInput 的 set/unset 动态 partial update。
     */
    fun update(ctx: SvcCtx, appIdVal: UUID, input: UpdateTodoItemInput) {
        ctx.mapper<CoreTodoItemMapper>().update {
            input.set?.content?.let { set(content) equalTo it }
            input.set?.done?.let { set(done) equalTo it }
            input.set?.note?.let { /* note 列在 todo_item 表暂无，预留 */ }
            if (input.unset?.contains(TodoItemUnsetField.NOTE) == true) {
                // note 列预留
            }
            set(updatedAt) equalTo Instant.now()
            where {
                id.isEqualTo(input.id)
                appId.isEqualTo(appIdVal)
                deletedAt.isNull()
            }
        }
    }

    fun deleteById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID): Boolean {
        val rows = ctx.mapper<CoreTodoItemMapper>().update {
            set(deletedAt) equalTo Instant.now()
            where {
                id.isEqualTo(idVal)
                appId.isEqualTo(appIdVal)
                deletedAt.isNull()
            }
        }
        return rows > 0
    }
}
