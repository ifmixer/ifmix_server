package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.generated.types.TodoFilter
import com.ifmix.api.core.generated.types.TodoUnsetField
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.infra.mybatis.CrudRepoTemplate
import com.ifmix.api.core.modules.demo.entity.TodoEntity
import com.ifmix.api.core.modules.demo.repo.mybatis.TodoMapper
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.insert
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.update
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class TodoMybatisRepo(private val mapper: TodoMapper) {

    private val t = TodoDynamicSql
    private val tpl = CrudRepoTemplate<TodoEntity>(
        meta = t.meta_,
        selectMany = mapper::selectMany,
        selectOne = mapper::selectOne,
        doUpdate = mapper::update,
        doDelete = mapper::delete,
    )

    // --- CRUD via template ---

    fun findById(appId: UUID, id: UUID): TodoEntity? = tpl.findById(appId, id)
    fun findByIds(appId: UUID, ids: Collection<UUID>): List<TodoEntity> = tpl.findByIds(appId, ids)
    fun exists(appId: UUID, id: UUID): Boolean = tpl.exists(appId, id)
    fun deleteById(appId: UUID, id: UUID): Boolean = tpl.softDeleteById(appId, id)
    fun deleteByIds(appId: UUID, ids: Collection<UUID>): Int = tpl.softDeleteByIds(appId, ids)

    fun findByCursor(appId: UUID, cursor: UUID?, limit: Int, filter: TodoFilter? = null): Page<TodoEntity> {
        return tpl.findByCursor(appId, cursor, limit) {
            filter?.done?.let { and { t.done isEqualTo it } }
            filter?.userId?.let { and { t.userId isEqualTo it } }
        }
    }

    // --- Custom ---

    fun insert(entity: TodoEntity): Int {
        return insert(mapper::insert, entity, t) {
            map(t.id) toProperty "id"
            map(t.appId) toProperty "appId"
            map(t.title) toProperty "title"
            map(t.done) toProperty "done"
            map(t.installId) toProperty "installId"
            map(t.userId) toProperty "userId"
            map(t.note) toProperty "note"
            map(t.meta) toProperty "meta"
            map(t.createdAt) toProperty "createdAt"
            map(t.updatedAt) toProperty "updatedAt"
        }
    }

    fun partialUpdate(appId: UUID, input: UpdateTodoInput) {
        val set = input.set
        val unset = input.unset?.toSet() ?: emptySet()
        if (set == null && unset.isEmpty()) return

        mapper.update(update(t) {
            if (TodoUnsetField.NOTE in unset) {
                set(t.note) equalToOrNull null as String?
            } else {
                set?.note?.let { set(t.note) equalTo it }
            }
            set?.title?.let { set(t.title) equalTo it }
            set?.done?.let { set(t.done) equalTo it }
            set(t.updatedAt) equalTo Instant.now()
            where {
                t.appId isEqualTo appId
                and { t.id isEqualTo input.id }
            }
        })
    }
}
