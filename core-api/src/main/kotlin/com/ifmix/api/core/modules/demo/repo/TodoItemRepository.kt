package com.ifmix.api.core.modules.demo.repo

import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.kotlin.KtUpdateWrapper
import com.ifmix.api.core.entity.demo.TodoItem
import com.ifmix.api.core.generated.types.UpdateTodoItemInput
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.mybatis.MybatisCrudOps
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * TodoItem Repository — 通用 CRUD 委托 MybatisCrudOps，业务特有查询手写 Wrapper。
 */
@Repository
class TodoItemRepository {

    private val ops = MybatisCrudOps(
        entityClass = TodoItem::class.java,
        appIdProp = TodoItem::appId,
        idProp = TodoItem::id,
        mapperFn = { ctx -> ctx.mapper<TodoItemMapper>() },
    )

    // ===== 通用 CRUD（一行委托） =====
    fun findById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID) = ops.findById(ctx, appIdVal, idVal)
    fun findByIds(ctx: SvcCtx, appIdVal: UUID, ids: Collection<UUID>) = ops.findByIds(ctx, appIdVal, ids)
    fun findByCursor(ctx: SvcCtx, appIdVal: UUID, cursor: UUID?, limit: Int) = ops.findByCursor(ctx, appIdVal, cursor, limit)
    fun insert(ctx: SvcCtx, entity: TodoItem) = ops.insert(ctx, entity)
    fun deleteById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID) = ops.deleteById(ctx, appIdVal, idVal)
    fun exists(ctx: SvcCtx, appIdVal: UUID, idVal: UUID) = ops.exists(ctx, appIdVal, idVal)

    // ===== 业务特有：按 todoId 查子项 =====
    fun findByTodoId(ctx: SvcCtx, todoIdVal: UUID): List<TodoItem> =
        ctx.mapper<TodoItemMapper>().selectList(
            KtQueryWrapper(TodoItem::class.java).apply { eq(true, TodoItem::todoId, todoIdVal) }
        )

    // ===== 业务特有：Partial Update =====
    fun update(ctx: SvcCtx, appIdVal: UUID, input: UpdateTodoItemInput) {
        val wrapper = KtUpdateWrapper(TodoItem::class.java)
            .eq(true, TodoItem::id, input.id)
            .eq(true, TodoItem::appId, appIdVal)
        input.set?.content?.let { wrapper.set(true, TodoItem::content, it, null) }
        input.set?.done?.let { wrapper.set(true, TodoItem::done, it, null) }
        ctx.mapper<TodoItemMapper>().update(null, wrapper)
    }
}
