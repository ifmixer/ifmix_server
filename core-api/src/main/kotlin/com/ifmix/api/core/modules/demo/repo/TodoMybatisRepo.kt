package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.generated.types.TodoFilter
import com.ifmix.api.core.generated.types.TodoUnsetField
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.infra.mybatis.CrudRepoTemplate
import com.ifmix.api.core.modules.demo.entity.TodoEntity
import com.ifmix.api.core.modules.demo.entity.TodoItemEntity
import com.ifmix.api.core.modules.demo.entity.TodoJoinRow
import com.ifmix.api.core.modules.demo.repo.mybatis.TodoMapper
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.insert
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.select
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.update
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class TodoMybatisRepo(
    private val mapper: TodoMapper,
    private val itemRepo: TodoItemMybatisRepo,
) {

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

    /**
     * 条件查询 todo 并带上 items 返回。
     */
    fun findTodoWithItems(appId: UUID, filter: TodoFilter? = null): List<TodoWithItems> {
        val provider = select(t.allColumns) {
            from(t)
            where {
                t.appId isEqualTo appId
                and { t.deletedAt.isNull() }
                filter?.done?.let { and { t.done isEqualTo it } }
                filter?.userId?.let { and { t.userId isEqualTo it } }
            }
            orderBy(t.id.descending())
        }
        val todos = mapper.selectMany(provider)
        if (todos.isEmpty()) return emptyList()

        val itemsByTodoId = itemRepo.findByTodoIds(appId, todos.map { it.id })
            .groupBy { it.todoId }

        return todos.map { todo ->
            TodoWithItems(todo = todo, items = itemsByTodoId[todo.id] ?: emptyList())
        }
    }

    // ==================== JOIN 演示 ====================

    /**
     * 演示 EXISTS 子查询 — 查找「有未完成 item」的 todo。
     *
     * SQL 等价:
     * ```sql
     * SELECT t.* FROM core_todo t
     * WHERE t.app_id = ? AND t.deleted_at IS NULL
     *   AND EXISTS (
     *     SELECT 1 FROM core_demo_item i
     *     WHERE i.todo_id = t.id AND i.done = false AND i.deleted_at IS NULL
     *   )
     * ORDER BY t.id DESC
     * ```
     */
    fun findTodosHavingUnfinishedItems(appId: UUID): List<TodoEntity> {
        val i = TodoItemDynamicSql
        val provider = select(t.allColumns) {
            from(t)
            where {
                t.appId isEqualTo appId
                and { t.deletedAt.isNull() }
                and {
                    exists {
                        select(i.id) {
                            from(i)
                            where {
                                i.todoId isEqualTo t.id
                                and { i.done isEqualTo false }
                                and { i.deletedAt.isNull() }
                            }
                        }
                    }
                }
            }
            orderBy(t.id.descending())
        }
        return mapper.selectMany(provider)
    }

    /**
     * 演示 LEFT JOIN — 一次 SQL 查出 todo + items 的扁平行，再聚合。
     *
     * SQL 等价:
     * ```sql
     * SELECT t.id, t.app_id, t.title, t.done, t.install_id, t.user_id, t.note,
     *        t.created_at, t.updated_at,
     *        i.id AS item_id, i.content AS item_content,
     *        i.done AS item_done, i.created_at AS item_created_at
     * FROM core_todo t
     * LEFT JOIN core_demo_item i ON i.todo_id = t.id AND i.deleted_at IS NULL
     * WHERE t.app_id = ? AND t.deleted_at IS NULL
     * ORDER BY t.id DESC, i.id
     * ```
     */
    fun findTodoWithItemsByJoin(appId: UUID, filter: TodoFilter? = null): List<TodoWithItems> {
        val i = TodoItemDynamicSql

        val provider = select(
            t.id, t.appId, t.title, t.done, t.installId, t.userId, t.note,
            t.createdAt, t.updatedAt,
            i.id.qualifiedWith("i").`as`("item_id"),
            i.content.qualifiedWith("i").`as`("item_content"),
            i.done.qualifiedWith("i").`as`("item_done"),
            i.createdAt.qualifiedWith("i").`as`("item_created_at"),
            i.deletedAt.qualifiedWith("i").`as`("item_deleted_at"),
        ) {
            from(t, "t")
            leftJoin(i, "i") {
                on(i.todoId) equalTo t.id
            }
            // ponytail: JoinCollector 不支持 isNull，item soft-delete 在 Kotlin 侧过滤
            where {
                t.appId isEqualTo appId
                and { t.deletedAt.isNull() }
                filter?.done?.let { and { t.done isEqualTo it } }
                filter?.userId?.let { and { t.userId isEqualTo it } }
            }
            orderBy(t.id.descending(), i.id)
        }
        val rows = mapper.selectJoinRows(provider)
        if (rows.isEmpty()) return emptyList()

        // 按 todo id 聚合扁平行
        return rows.groupBy { it.id }.map { (_, group) ->
            val first = group.first()
            TodoWithItems(
                todo = TodoEntity(
                    id = first.id,
                    appId = first.appId,
                    title = first.title,
                    done = first.done,
                    installId = first.installId,
                    userId = first.userId,
                    note = first.note,
                    createdAt = first.createdAt,
                    updatedAt = first.updatedAt,
                ),
                items = group.mapNotNull { row ->
                    // 过滤 soft-deleted items（ON 条件不支持 IS NULL，在此过滤）
                    if (row.itemId == null || row.itemDeletedAt != null) return@mapNotNull null
                    TodoItemEntity(
                        id = row.itemId,
                        appId = first.appId,
                        todoId = first.id,
                        content = row.itemContent!!,
                        done = row.itemDone!!,
                        createdAt = row.itemCreatedAt!!,
                        updatedAt = row.itemCreatedAt,
                    )
                },
            )
        }
    }
}

data class TodoWithItems(
    val todo: TodoEntity,
    val items: List<TodoItemEntity>,
)
