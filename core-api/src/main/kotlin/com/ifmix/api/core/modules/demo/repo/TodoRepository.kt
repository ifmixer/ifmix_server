package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.mybatis.MybatisCrudOps
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.appId
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.deletedAt
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.done
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.id
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.note
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.title
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.updatedAt
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoMapper
import com.ifmix.api.core.generated.mybatis.mapper.count
import com.ifmix.api.core.generated.mybatis.mapper.insert
import com.ifmix.api.core.generated.mybatis.mapper.select
import com.ifmix.api.core.generated.mybatis.mapper.selectOne
import com.ifmix.api.core.generated.mybatis.mapper.update
import com.ifmix.api.core.generated.mybatis.model.CoreTodo
import com.ifmix.api.core.generated.types.TodoUnsetField
import com.ifmix.api.core.generated.types.UpdateTodoInput
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class TodoRepository {

    private val ops = MybatisCrudOps<CoreTodo, CoreTodoMapper>(
        id = id, appId = appId, deletedAt = deletedAt,
        mapperFn = { ctx -> ctx.mapper() },
        selectOneFn = CoreTodoMapper::selectOne,
        selectListFn = CoreTodoMapper::select,
        countFn = CoreTodoMapper::count,
        updateFn = CoreTodoMapper::update,
        insertFn = CoreTodoMapper::insert,
    )

    fun findById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID) = ops.findById(ctx, appIdVal, idVal)
    fun findByIds(ctx: SvcCtx, appIdVal: UUID, ids: Collection<UUID>) = ops.findByIds(ctx, appIdVal, ids)
    fun findByCursor(ctx: SvcCtx, appIdVal: UUID, cursor: UUID?, limit: Int) = ops.findByCursor(ctx, appIdVal, cursor, limit)
    fun insert(ctx: SvcCtx, entity: CoreTodo) = ops.insert(ctx, entity)
    fun deleteById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID) = ops.deleteById(ctx, appIdVal, idVal)
    fun exists(ctx: SvcCtx, appIdVal: UUID, idVal: UUID) = ops.exists(ctx, appIdVal, idVal)

    /** Partial update — 根据 GraphQL input 的 set/unset 动态构建 */
    fun update(ctx: SvcCtx, appIdVal: UUID, input: UpdateTodoInput) {
        ctx.mapper<CoreTodoMapper>().update {
            input.set?.title?.let { set(title) equalTo it }
            input.set?.done?.let { set(done) equalTo it }
            input.set?.note?.let { set(note) equalTo it }
            if (input.unset?.contains(TodoUnsetField.NOTE) == true) {
                set(note).equalToNull()
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
