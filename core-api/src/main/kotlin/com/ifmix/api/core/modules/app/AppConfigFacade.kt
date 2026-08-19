package com.ifmix.api.core.modules.app

import com.ifmix.api.core.entity.appconfig.dto.AppConfigRevisionCreateInput
import com.ifmix.api.core.entity.appconfig.dto.AppConfigRevisionDto
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.modules.app.repo.AppConfigRevisionRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AppConfigFacade(
    private val revisionRepo: AppConfigRevisionRepository,
) {

    /** 写操作 — 有事务 */
    fun createOneRevision(ctx: OperationContext, req: AppConfigRevisionCreateInput): AppConfigRevisionDto {
        val appId = ctx.mustGetAppId()

        if (req.enabled) {
            revisionRepo.disableCurrentRevisions(ctx.repoCtx, appId)
        }

        val created = revisionRepo.createNewRevision(ctx.repoCtx, req)

        return revisionRepo.findById(ctx.repoCtx, appId, created.id, AppConfigRevisionDto::class)
            ?: throw ApiError(ErrorCode.INTERNAL, "failed to read newly created revision")
    }

    /** 写操作 — 有事务 */
    fun toggleRevision(ctx: OperationContext, revisionId: UUID, enabled: Boolean): AppConfigRevisionDto {
        val appId = ctx.mustGetAppId()

        revisionRepo.findById(ctx.repoCtx, appId, revisionId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "revision not found")

        if (enabled) {
            revisionRepo.disableCurrentRevisions(ctx.repoCtx, appId)
        }

        revisionRepo.updateEnabled(ctx.repoCtx, revisionId, enabled)

        return revisionRepo.findById(ctx.repoCtx, appId, revisionId, AppConfigRevisionDto::class)
            ?: throw ApiError(ErrorCode.INTERNAL, "failed to read revision after toggle")
    }
}
