package com.ifmix.api.core.modules.demo.repo

import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.kotlin.KtUpdateWrapper
import com.ifmix.api.core.entity.demo.Todo
import com.ifmix.api.core.generated.types.TodoUnsetField
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.mybatis.MybatisCrudOps
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Todo Repository — 通用 CRUD 委托 MybatisCrudOps，业务特有查询手写 Wrapper。
 */
@Repository
class TodoRepository {

    private val ops = MybatisCrudOps(
        entityClass = Todo::class.java,
        appIdProp = Todo::appId,
        idProp = Todo::id,
        mapperFn = { ctx -> ctx.mapper<TodoMapper>() },
    )

    // ===== 通用 CRUD（一行委托） =====
    fun findById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID) = ops.findById(ctx, appIdVal, idVal)
    fun findByIds(ctx: SvcCtx, appIdVal: UUID, ids: Collection<UUID>) = ops.findByIds(ctx, appIdVal, ids)
    fun findByCursor(ctx: SvcCtx, appIdVal: UUID, cursor: UUID?, limit: Int) = ops.findByCursor(ctx, appIdVal, cursor, limit)
    fun insert(ctx: SvcCtx, entity: Todo) = ops.insert(ctx, entity)
    fun deleteById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID) = ops.deleteById(ctx, appIdVal, idVal)
    fun exists(ctx: SvcCtx, appIdVal: UUID, idVal: UUID) = ops.exists(ctx, appIdVal, idVal)

    // ===== 业务特有：Partial Update =====
    fun update(ctx: SvcCtx, appIdVal: UUID, input: UpdateTodoInput) {
        val wrapper = KtUpdateWrapper(Todo::class.java)
            .eq(true, Todo::id, input.id)
            .eq(true, Todo::appId, appIdVal)
        input.set?.title?.let { wrapper.set(true, Todo::title, it, null) }
        input.set?.done?.let { wrapper.set(true, Todo::done, it, null) }
        input.set?.note?.let { wrapper.set(true, Todo::note, it, null) }
        if (input.unset?.contains(TodoUnsetField.NOTE) == true) wrapper.set(true, Todo::note, null as Any?, null)
        ctx.mapper<TodoMapper>().update(null, wrapper)
    }
}
