package com.ifmix.api.core.modules.demo

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.entity.todo.Todo
import com.ifmix.api.core.generated.types.CreateTodoItemInput
import com.ifmix.api.core.generated.types.FilterGroup
import com.ifmix.api.core.generated.types.TodoFilter
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.generated.types.UpdateTodoItemsMutationInput
import com.ifmix.api.core.infra.db.SvcCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.tx.TxRunner
import com.ifmix.api.core.modules.demo.handler.TodoHandler
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DemoFacade(
    private val svcCtxFactory: SvcCtxFactory,
    private val handler: TodoHandler,
    private val tx: TxRunner,
) {
    // --- Queries (no tx) ---

    fun findById(ctx: OperationContext, id: UUID): Todo? =
        handler.findById(svcCtxFactory.forApp(ctx), ctx.mustGetAppId(), id)

    fun findByIds(ctx: OperationContext, ids: List<UUID>): List<Todo> =
        handler.findByIds(svcCtxFactory.forApp(ctx), ctx.mustGetAppId(), ids)

    fun findByCursor(ctx: OperationContext, cursor: UUID?, limit: Int, filter: TodoFilter? = null): Page<Todo> =
        handler.findByCursor(svcCtxFactory.forApp(ctx), ctx.mustGetAppId(), cursor, limit, filter)

    fun findByFilter(ctx: OperationContext, filter: FilterGroup?, cursor: UUID?, limit: Int): Page<Todo> =
        handler.findByFilter(svcCtxFactory.forApp(ctx), ctx.mustGetAppId(), filter, cursor, limit)

    // --- Mutations (with tx) ---

    fun create(ctx: OperationContext, title: String, done: Boolean?, note: String?, items: List<CreateTodoItemInput>?): Todo =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> handler.create(sc, title, done, note, items) }

    fun partialUpdate(ctx: OperationContext, input: UpdateTodoInput) {
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> handler.partialUpdate(sc, ctx.mustGetAppId(), input) }
    }

    fun batchUpdateItems(ctx: OperationContext, input: UpdateTodoItemsMutationInput) {
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> handler.batchUpdateItems(sc, ctx.mustGetAppId(), input) }
    }

    fun deleteById(ctx: OperationContext, id: UUID): Boolean =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> handler.deleteById(sc, ctx.mustGetAppId(), id) }

    fun batchDelete(ctx: OperationContext, ids: List<UUID>): Int =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> handler.batchDelete(sc, ctx.mustGetAppId(), ids) }
}
