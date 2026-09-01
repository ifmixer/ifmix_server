package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.entity.demo.Todo
import com.ifmix.api.core.entity.demo.TodoItem
import com.ifmix.api.core.entity.demo.TodoRecommend
import com.ifmix.api.core.entity.demo.TodoProps
import com.ifmix.api.core.entity.demo.toDomain
import com.ifmix.api.core.entity.demo.appId
import com.ifmix.api.core.entity.demo.id
import com.ifmix.api.core.entity.demo.title
import com.ifmix.api.core.entity.demo.done
import com.ifmix.api.core.entity.demo.note
import com.ifmix.api.core.entity.demo.recommend
import com.ifmix.api.core.entity.demo.customerId
import com.ifmix.api.core.entity.demo.todoId
import com.ifmix.api.core.generated.types.CommonFindOptions
import com.ifmix.api.core.generated.types.TodoRecommendInput
import com.ifmix.api.core.generated.types.TodoUnsetField
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.AppCrudRepoTemplate
import org.babyfish.jimmer.sql.ast.mutation.DeleteMode
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.babyfish.jimmer.sql.kt.ast.table.KNonNullTable
import org.babyfish.jimmer.sql.kt.ast.table.KWeakJoin
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TodoRepository {

    companion object {
        private val tpl = AppCrudRepoTemplate(Todo::class)

        /** 允许前端通过 FilterGroup 查询的字段（强类型白名单） */
        val FILTERABLE = listOf(
            TodoProps.TITLE,
            TodoProps.DONE,
            TodoProps.CUSTOMER_ID,
            TodoProps.NOTE,
            TodoProps.CREATED_AT,
            TodoProps.UPDATED_AT,
        )
    }

    fun findById(mc: ModuleCtx, appId: UUID, id: UUID): Todo? = tpl.findById(mc, appId, id)
    fun findByIds(mc: ModuleCtx, appId: UUID, ids: Collection<UUID>): List<Todo> = tpl.findByIds(mc, appId, ids)
    fun save(mc: ModuleCtx, entity: Todo) = tpl.save(mc, entity)
    fun deleteById(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.deleteById(mc, appId, id)
    fun deleteByIds(mc: ModuleCtx, appId: UUID, ids: Collection<UUID>): Int = tpl.deleteByIds(mc, appId, ids)

    /** 合并：把 fromCustomerId 名下 todo 归属改到 toCustomerId。返回改写行数。 */
    fun reassignOwner(mc: ModuleCtx, appId: UUID, fromCustomerId: UUID, toCustomerId: UUID): Int =
        mc.sql.createUpdate(Todo::class) {
            where(table.appId eq appId)
            where(table.customerId eq fromCustomerId)
            set(table.customerId, toCustomerId)
        }.execute()

    /** 阶段 6：物理删除某批 customer 名下 todo（含软删列，显式 PHYSICAL 硬删避免孤儿行）。 */
    fun physicalDeleteByCustomers(mc: ModuleCtx, appId: UUID, customerIds: Collection<UUID>): Int {
        if (customerIds.isEmpty()) return 0
        return mc.sql.createDelete(Todo::class) {
            setMode(DeleteMode.PHYSICAL)
            where(table.appId eq appId)
            where(table.customerId valueIn customerIds)
        }.execute()
    }

    fun findByOptions(mc: ModuleCtx, appId: UUID, findOptions: CommonFindOptions?): Page<Todo> =
        tpl.findByOptions(mc, appId, findOptions, FILTERABLE)

    fun partialUpdate(mc: ModuleCtx, appId: UUID, input: UpdateTodoInput): Int {
        val set = input.set
        val unset = input.unset?.toSet() ?: emptySet()

        // 没有任何更新请求
        if (set == null && unset.isEmpty()) return 0

        return mc.sql.createUpdate(Todo::class) {
            where(table.appId eq appId)
            where(table.id eq input.id)

            // unset 优先：如果字段同时出现在 set 和 unset，以 unset 为准
            if (TodoUnsetField.NOTE in unset) {
                set(table.note, null as String?)
            } else {
                set?.note?.let { set(table.note, it) }
            }

            if (TodoUnsetField.RECOMMEND in unset) {
                set(table.recommend, null as TodoRecommend?)
            } else {
                set?.recommend?.let { set(table.recommend, it.toDomain()) }
            }

            if (TodoUnsetField.NOTE !in unset && TodoUnsetField.RECOMMEND !in unset) {
                set?.title?.let { set(table.title, it) }
                set?.done?.let { set(table.done, it) }
            }
        }.execute()
    }

    // ===== 手动 Join 演示 =====

    /**
     * 演示：查询包含未完成子项的 Todo 列表。
     *
     * 由于 Todo 和 TodoItem 之间没有 Jimmer @ManyToOne/@OneToMany 关联注解
     * （只有 TodoItem.todoId 作为逻辑外键），不能用 `table.items` 隐式路径，
     * 需要通过 KWeakJoin 手动定义 join 条件。
     *
     * 生成 SQL:
     * ```sql
     * SELECT DISTINCT t.* FROM core_todo t
     * INNER JOIN core_demo_item ti ON t.id = ti.todo_id
     * WHERE t.app_id = ? AND ti.done = false
     * ORDER BY t.id DESC
     * ```
     */
    fun findWithPendingItems(mc: ModuleCtx, appId: UUID, cursor: UUID?, limit: Int): Page<Todo> {
        val rows = mc.sql.createQuery(Todo::class) {
            where(table.appId eq appId)

            // 手动 join TodoItem 表
            val itemTable = table.asTableEx().weakJoin(TodoToItemJoin::class)
            where(itemTable.done eq false)

            cursor?.let { where(table.id lt it) }
            orderBy(table.id.desc())
            select(table)
        }.distinct().limit(limit + 1).execute()

        return Page.of(rows, limit) { it.id.toString() }
    }

    /**
     * 演示：统计每个 Todo 的子项数量（GROUP BY + COUNT）。
     *
     * 返回 Pair<Todo, Long>（todo 对象 + 子项计数）。
     */
    fun findWithItemCount(mc: ModuleCtx, appId: UUID, cursor: UUID?, limit: Int): List<Pair<Todo, Long>> {
        return mc.sql.createQuery(Todo::class) {
            where(table.appId eq appId)

            val itemTable = table.asTableEx().weakJoin(TodoToItemJoin::class)

            cursor?.let { where(table.id lt it) }
            orderBy(table.id.desc())
            groupBy(table.id)
            select(table, count(itemTable.id))
        }.limit(limit).execute().map { (todo, cnt) -> todo to cnt }
    }
}

/**
 * Todo → TodoItem 的手动 join 条件定义。
 *
 * Jimmer KWeakJoin 适用于两个 entity 之间没有关联注解、
 * 但需要在查询中 join 的场景。join 条件在此处集中定义一次，
 * 各查询通过 `table.asTableEx().weakJoin(TodoToItemJoin::class)` 复用。
 */
class TodoToItemJoin : KWeakJoin<Todo, TodoItem>() {
    override fun on(
        source: KNonNullTable<Todo>,
        target: KNonNullTable<TodoItem>,
    ): KNonNullExpression<Boolean> =
        source.id eq target.todoId
}
