package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.generated.mybatis.mapper.*
import com.ifmix.api.core.generated.mybatis.mapper.TodoDynamicSqlSupport.appId
import com.ifmix.api.core.generated.mybatis.mapper.TodoDynamicSqlSupport.deletedAt
import com.ifmix.api.core.generated.mybatis.mapper.TodoDynamicSqlSupport.done
import com.ifmix.api.core.generated.mybatis.mapper.TodoDynamicSqlSupport.id
import com.ifmix.api.core.generated.mybatis.mapper.TodoDynamicSqlSupport.note
import com.ifmix.api.core.generated.mybatis.mapper.TodoDynamicSqlSupport.title
import com.ifmix.api.core.generated.mybatis.mapper.TodoDynamicSqlSupport.updatedAt
import com.ifmix.api.core.generated.mybatis.mapper.TodoDynamicSqlSupport.userId
import com.ifmix.api.core.generated.mybatis.model.Todo
import com.ifmix.api.core.generated.types.TodoFilter
import com.ifmix.api.core.generated.types.TodoUnsetField
import com.ifmix.api.core.generated.types.UpdateTodoInput
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Phase 0: Mapper 由 Spring 注入。
 * Phase 2: 改为从 ModuleCtx.session 动态获取。
 */
@Repository
class TodoRepository(private val mapper: TodoMapper) {

    fun findById(appId_: UUID, id_: UUID): Todo? =
        mapper.selectOne {
            where { id.isEqualTo(id_); appId.isEqualTo(appId_); deletedAt.isNull() }
        }

    fun findByIds(appId_: UUID, ids: Collection<UUID>): List<Todo> {
        if (ids.isEmpty()) return emptyList()
        return mapper.select {
            where { id.isIn(ids.toList()); appId.isEqualTo(appId_); deletedAt.isNull() }
        }
    }

    fun insert(entity: Todo) = mapper.insert(entity)

    fun deleteById(appId_: UUID, id_: UUID): Boolean =
        mapper.update {
            set(deletedAt) equalTo Instant.now()
            where { id.isEqualTo(id_); appId.isEqualTo(appId_) }
        } > 0

    fun deleteByIds(appId_: UUID, ids: Collection<UUID>): Int {
        if (ids.isEmpty()) return 0
        return mapper.update {
            set(deletedAt) equalTo Instant.now()
            where { id.isIn(ids.toList()); appId.isEqualTo(appId_) }
        }
    }

    fun findByCursor(appId_: UUID, cursor: UUID?, limit: Int, filter: TodoFilter? = null): Page<Todo> {
        val rows = mapper.select {
            where {
                appId.isEqualTo(appId_)
                deletedAt.isNull()
                if (cursor != null) id.isLessThan(cursor)
                filter?.done?.let { done.isEqualTo(it) }
                filter?.userId?.let { userId.isEqualTo(it) }
            }
            orderBy(id.descending())
            limit(limit.toLong() + 1)
        }
        val hasMore = rows.size > limit
        val items = if (hasMore) rows.dropLast(1) else rows
        val nextCursor = items.lastOrNull()?.id?.toString()
        return Page(items = items, nextCursor = nextCursor, hasMore = hasMore)
    }

    fun partialUpdate(appId_: UUID, input: UpdateTodoInput) {
        val set = input.set
        val unset = input.unset?.toSet() ?: emptySet()
        if (set == null && unset.isEmpty()) return

        mapper.update {
            // unset 优先
            if (TodoUnsetField.NOTE in unset) {
                set(note).equalToNull()
            } else {
                set?.note?.let { set(note) equalTo it }
            }
            set?.title?.let { set(title) equalTo it }
            set?.done?.let { set(done) equalTo it }
            set(updatedAt) equalTo Instant.now()
            where { id.isEqualTo(input.id); appId.isEqualTo(appId_) }
        }
    }
}
