package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.mybatis.MybatisCrudOps
import com.ifmix.api.core.modules.demo.mybatis.mapper.CoreTodoItemMapper
import com.ifmix.api.core.modules.demo.mybatis.model.CoreTodoItem
import com.ifmix.api.core.generated.types.TodoItemUnsetField
import com.ifmix.api.core.generated.types.UpdateTodoItemInput
import org.mybatis.dynamic.sql.AliasableSqlTable
import org.mybatis.dynamic.sql.util.kotlin.elements.column
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.selectList
import org.mybatis.dynamic.sql.util.kotlin.mybatis3.update
import org.springframework.stereotype.Repository
import java.sql.JDBCType
import java.time.Instant
import java.util.UUID

@Repository
class TodoItemRepository {

    // ─── Table & Column definitions ───────────────────────────────────────

    private class TodoItemTable : AliasableSqlTable<TodoItemTable>("core_todo_item", ::TodoItemTable) {
        val id = column<UUID>(name = "id", jdbcType = JDBCType.OTHER)
        val todoId = column<UUID>(name = "todo_id", jdbcType = JDBCType.OTHER)
        val appId = column<UUID>(name = "app_id", jdbcType = JDBCType.OTHER)
        val content = column<String>(name = "content", jdbcType = JDBCType.VARCHAR)
        val done = column<Boolean>(name = "done", jdbcType = JDBCType.BIT)
        val createdAt = column<Instant>(name = "created_at", jdbcType = JDBCType.OTHER)
        val updatedAt = column<Instant>(name = "updated_at", jdbcType = JDBCType.OTHER)
        val deletedAt = column<Instant>(name = "deleted_at", jdbcType = JDBCType.OTHER)
    }

    private val t = TodoItemTable()

    private val allColumns = listOf(
        t.id, t.todoId, t.appId, t.content,
        t.done, t.createdAt, t.updatedAt, t.deletedAt,
    )

    // ─── Generic CRUD via MybatisCrudOps ──────────────────────────────────

    private val ops = MybatisCrudOps.forTable<CoreTodoItem, CoreTodoItemMapper>(
        table = t, columns = allColumns,
        idColumn = t.id, appIdColumn = t.appId, deletedAtColumn = t.deletedAt,
        mapperFn = { ctx -> ctx.mapper() },
        selectMany = CoreTodoItemMapper::selectMany,
        selectOne = CoreTodoItemMapper::selectOne,
        insert = CoreTodoItemMapper::insert,
    )

    // ─── Public API ───────────────────────────────────────────────────────

    fun findById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID) = ops.findById(ctx, appIdVal, idVal)
    fun insert(ctx: SvcCtx, entity: CoreTodoItem) = ops.insert(ctx, entity)
    fun deleteById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID) = ops.deleteById(ctx, appIdVal, idVal)

    /** 按 todoId 查子项 */
    fun findByTodoId(ctx: SvcCtx, todoIdVal: UUID): List<CoreTodoItem> =
        ctx.mapper<CoreTodoItemMapper>().let { mapper ->
            selectList(mapper::selectMany, allColumns, t) {
                where { t.todoId.isEqualTo(todoIdVal); t.deletedAt.isNull() }
                orderBy(t.createdAt)
            }
        }

    /** Partial update */
    fun update(ctx: SvcCtx, appIdVal: UUID, input: UpdateTodoItemInput) {
        update(ctx.mapper<CoreTodoItemMapper>()::update, t) {
            input.set?.content?.let { set(t.content) equalTo it }
            input.set?.done?.let { set(t.done) equalTo it }
            if (input.unset?.contains(TodoItemUnsetField.NOTE) == true) { /* 预留 */ }
            set(t.updatedAt) equalTo Instant.now()
            where { t.id.isEqualTo(input.id); t.appId.isEqualTo(appIdVal); t.deletedAt.isNull() }
        }
    }
}
