package com.ifmix.api.core.modules.demo

import com.ifmix.api.core.infra.db.SvcCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.demo.handler.TodoHandler
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DemoFacade(
    private val svcCtxFactory: SvcCtxFactory,
    private val handler: TodoHandler,
) {
    fun findById(ctx: OperationContext, id: UUID) =
        handler.findById(svcCtxFactory.forApp(ctx), ctx.mustGetAppId(), id)

    fun findByCursor(ctx: OperationContext, cursor: UUID?, limit: Int) =
        handler.findByCursor(svcCtxFactory.forApp(ctx), ctx.mustGetAppId(), cursor, limit)

    fun create(ctx: OperationContext, title: String, done: Boolean?) =
        handler.create(svcCtxFactory.forApp(ctx), title, done)

    fun partialUpdate(ctx: OperationContext, id: UUID, title: String?, done: Boolean?) {
        handler.partialUpdate(svcCtxFactory.forApp(ctx), ctx.mustGetAppId(), id, title, done)
    }

    fun deleteById(ctx: OperationContext, id: UUID) =
        handler.deleteById(svcCtxFactory.forApp(ctx), ctx.mustGetAppId(), id)
}
