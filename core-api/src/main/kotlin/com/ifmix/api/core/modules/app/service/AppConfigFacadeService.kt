package com.ifmix.api.core.modules.app.service

import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.app.repo.AppConfigRepository
import com.ifmix.api.core.model.app.AppConfigRevision
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AppConfigFacadeService(
    private val commands: AppConfigCommands,
) {
    fun createOneRevision(ctx: OperationContext, req: AppConfigCommands.CreateRevisionInput): AppConfigCommands.RevisionDto =
        commands.createOneRevision(ctx, req)

    fun toggleRevision(ctx: OperationContext, revisionId: UUID, enabled: Boolean): AppConfigCommands.RevisionDto =
        commands.toggleRevision(ctx, revisionId, enabled)
}
