package com.ifmix.api.core.modules.demo.service

import com.ifmix.api.core.infra.db.SvcCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.demo.service.internal.TodoEntityService
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DemoModuleService(
    private val svcCtxFactory: SvcCtxFactory,
    private val entityService: TodoEntityService,
) {
    fun findById(ctx: OperationContext, id: UUID) =
        entityService.findById(svcCtxFactory.forApp(ctx), ctx.mustGetAppId(), id)

    fun findByCursor(ctx: OperationContext, cursor: UUID?, limit: Int) =
        entityService.findByCursor(svcCtxFactory.forApp(ctx), ctx.mustGetAppId(), cursor, limit)

    fun create(ctx: OperationContext, title: String, done: Boolean?) =
        entityService.create(svcCtxFactory.forApp(ctx), title, done)

    fun partialUpdate(ctx: OperationContext, id: UUID, title: String?, done: Boolean?) {
        entityService.partialUpdate(svcCtxFactory.forApp(ctx), ctx.mustGetAppId(), id, title, done)
    }

    fun deleteById(ctx: OperationContext, id: UUID) =
        entityService.deleteById(svcCtxFactory.forApp(ctx), ctx.mustGetAppId(), id)
}
