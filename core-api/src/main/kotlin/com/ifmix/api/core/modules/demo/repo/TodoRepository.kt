package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.appId
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.deletedAt
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.done
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.id
import com.ifmix.api.core.generated.mybatis.mapper.CoreTodoDynamicSqlSupport.meta
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

/**
 * Todo 数据访问层。
 */
@Repository
class TodoRepository {

    fun findById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID): CoreTodo? =
        ctx.mapper<CoreTodoMapper>().selectOne {
            where {
                appId.isEqualTo(appIdVal)
                id.isEqualTo(idVal)
                deletedAt.isNull()
            }
        }

    fun findByIds(ctx: SvcCtx, appIdVal: UUID, ids: Collection<UUID>): List<CoreTodo> {
        if (ids.isEmpty()) return emptyList()
        return ctx.mapper<CoreTodoMapper>().select {
            where {
                appId.isEqualTo(appIdVal)
                id.isIn(ids.toList())
                deletedAt.isNull()
            }
        }
    }

    fun findByCursor(ctx: SvcCtx, appIdVal: UUID, cursor: UUID?, limit: Int): List<CoreTodo> =
        ctx.mapper<CoreTodoMapper>().select {
            where {
                appId.isEqualTo(appIdVal)
                deletedAt.isNull()
                if (cursor != null) {
                    id.isLessThan(cursor)
                }
            }
            orderBy(id.descending())
            limit(limit.toLong())
        }

    fun insert(ctx: SvcCtx, entity: CoreTodo) {
        ctx.mapper<CoreTodoMapper>().insert(entity)
    }

    /**
     * 根据 UpdateTodoInput 的 set/unset 动态 partial update。
     * - set 中非 null 的字段 → SET column = value
     * - unset 中列出的字段 → SET column = NULL
     */
    fun update(ctx: SvcCtx, appIdVal: UUID, input: UpdateTodoInput) {
        ctx.mapper<CoreTodoMapper>().update {
            // set
            input.set?.title?.let { set(title) equalTo it }
            input.set?.done?.let { set(done) equalTo it }
            input.set?.note?.let { set(note) equalTo it }
            // unset
            if (input.unset?.contains(TodoUnsetField.NOTE) == true) {
                set(note).equalToNull()
            }
            // always touch updatedAt
            set(updatedAt) equalTo Instant.now()
            where {
                id.isEqualTo(input.id)
                appId.isEqualTo(appIdVal)
                deletedAt.isNull()
            }
        }
    }

    fun deleteById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID): Boolean {
        val rows = ctx.mapper<CoreTodoMapper>().update {
            set(deletedAt) equalTo Instant.now()
            where {
                id.isEqualTo(idVal)
                appId.isEqualTo(appIdVal)
                deletedAt.isNull()
            }
        }
        return rows > 0
    }

    fun exists(ctx: SvcCtx, appIdVal: UUID, idVal: UUID): Boolean =
        ctx.mapper<CoreTodoMapper>().count {
            where {
                appId.isEqualTo(appIdVal)
                id.isEqualTo(idVal)
                deletedAt.isNull()
            }
        } > 0
}
