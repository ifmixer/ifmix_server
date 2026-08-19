package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.mybatis.MybatisCrudOps
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoItemDynamicSqlSupport.appId
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoItemDynamicSqlSupport.content
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoItemDynamicSqlSupport.createdAt
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoItemDynamicSqlSupport.deletedAt
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoItemDynamicSqlSupport.done
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoItemDynamicSqlSupport.id
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoItemDynamicSqlSupport.todoId
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoItemDynamicSqlSupport.updatedAt
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoItemMapper
import com.ifmix.api.core.generated.mybatis.mapper.count
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

@Repository
class TodoItemRepository {

    private val ops = MybatisCrudOps<CoreTodoItem, CoreTodoItemMapper>(
        id = id, appId = appId, deletedAt = deletedAt,
        mapperFn = { ctx -> ctx.mapper() },
        selectOneFn = CoreTodoItemMapper::selectOne,
        selectListFn = CoreTodoItemMapper::select,
        countFn = CoreTodoItemMapper::count,
        updateFn = CoreTodoItemMapper::update,
        insertFn = CoreTodoItemMapper::insert,
    )

    fun findById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID) = ops.findById(ctx, appIdVal, idVal)
    fun insert(ctx: SvcCtx, entity: CoreTodoItem) = ops.insert(ctx, entity)
    fun deleteById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID) = ops.deleteById(ctx, appIdVal, idVal)

    /** 按 todoId 查子项 */
    fun findByTodoId(ctx: SvcCtx, todoIdVal: UUID): List<CoreTodoItem> =
        ctx.mapper<CoreTodoItemMapper>().select {
            where {
                todoId.isEqualTo(todoIdVal)
                deletedAt.isNull()
            }
            orderBy(createdAt)
        }

    /** Partial update */
    fun update(ctx: SvcCtx, appIdVal: UUID, input: UpdateTodoItemInput) {
        ctx.mapper<CoreTodoItemMapper>().update {
            input.set?.content?.let { set(content) equalTo it }
            input.set?.done?.let { set(done) equalTo it }
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
}
