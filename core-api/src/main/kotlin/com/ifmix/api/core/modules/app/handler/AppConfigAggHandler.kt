package com.ifmix.api.core.modules.app.handler

import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.modules.app.repo.AppConfigRepository
import com.ifmix.api.core.entity.app.AppConfigRevision
import com.ifmix.api.core.entity.app.ConfigContent
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Component
class AppConfigAggHandler(
    private val revisionRepo: AppConfigRepository,
) {
    data class CreateRevisionInput(
        val authTenantId: UUID? = null,
        val appleBundleId: String? = null,
        val androidPackageName: String? = null,
        val content: ConfigContent,
        val revisionNumber: Int,
        val enabled: Boolean = false,
        val slug: String,
        val note: String,
    )

    fun createOneRevision(mc: ModuleCtx, req: CreateRevisionInput): AppConfigRevision {
        val appId = mc.appId!!
        if (req.enabled) revisionRepo.disableCurrentRevisions(mc, appId)
        val now = java.time.Instant.now()
        val revision = AppConfigRevision {
            id = UuidV7.generate()
            this.appId = appId
            this.authTenantId = req.authTenantId
            this.appleBundleId = req.appleBundleId
            this.androidPackageName = req.androidPackageName
            this.revisionNumber = req.revisionNumber
            this.createdAt = now
            this.enabled = req.enabled
            this.slug = req.slug
            this.content = req.content
            this.note = req.note
        }
        revisionRepo.save(mc, revision)
        return revision
    }

    fun toggleRevision(mc: ModuleCtx, revisionId: UUID, enabled: Boolean): AppConfigRevision {
        val appId = mc.appId!!
        val existing = revisionRepo.findById(mc, appId, revisionId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "revision not found")
        if (existing.appId != appId) throw ApiError(ErrorCode.NOT_FOUND, "revision not found")
        if (enabled) revisionRepo.disableCurrentRevisions(mc, appId)
        revisionRepo.updateEnabled(mc, revisionId, enabled)
        val updated = revisionRepo.findById(mc, appId, revisionId)
            ?: throw ApiError(ErrorCode.INTERNAL, "failed to read revision after toggle")
        return updated
    }

}
