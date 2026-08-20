package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.generated.types.UpdateTodoItemInput
import com.ifmix.api.core.infra.mybatis.CrudRepoTemplate
import com.ifmix.api.core.modules.demo.entity.TodoItemEntity
import com.ifmix.api.core.modules.demo.repo.mybatis.TodoItemMapper
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.insert
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.select
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.update
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class TodoItemMybatisRepo(private val mapper: TodoItemMapper) {

    private val t = TodoItemDynamicSql
    private val tpl = CrudRepoTemplate<TodoItemEntity>(
        meta = t.meta_,
        selectMany = mapper::selectMany,
        selectOne = mapper::selectOne,
        doUpdate = mapper::update,
        doDelete = mapper::delete,
    )

    // --- CRUD via template ---

    fun deleteByIds(appId: UUID, ids: Collection<UUID>): Int = tpl.softDeleteByIds(appId, ids)

    fun findByTodoIds(appId: UUID, todoIds: Collection<UUID>): List<TodoItemEntity> {
        if (todoIds.isEmpty()) return emptyList()
        val provider = select(t.allColumns) {
            from(t)
            where {
                t.appId isEqualTo appId
                and { t.todoId isIn todoIds.toList() }
                and { t.deletedAt.isNull() }
            }
        }
        return mapper.selectMany(provider)
    }

    // --- Custom ---

    fun insert(entity: TodoItemEntity): Int {
        return insert(mapper::insert, entity, t) {
            map(t.id) toProperty "id"
            map(t.appId) toProperty "appId"
            map(t.todoId) toProperty "todoId"
            map(t.content) toProperty "content"
            map(t.done) toProperty "done"
            map(t.createdAt) toProperty "createdAt"
            map(t.updatedAt) toProperty "updatedAt"
        }
    }

    fun partialUpdate(appId: UUID, input: UpdateTodoItemInput) {
        val set = input.set
        val unset = input.unset?.toSet() ?: emptySet()
        if (set == null && unset.isEmpty()) return

        mapper.update(update(t) {
            set?.content?.let { set(t.content) equalTo it }
            set?.done?.let { set(t.done) equalTo it }
            // note: DB table has no 'note' column; skip note handling
            set(t.updatedAt) equalTo Instant.now()
            where {
                t.appId isEqualTo appId
                and { t.id isEqualTo input.id }
            }
        })
    }
}
