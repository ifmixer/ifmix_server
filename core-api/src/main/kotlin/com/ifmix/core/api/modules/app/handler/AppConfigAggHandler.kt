package com.ifmix.core.api.modules.app.handler

import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.UuidV7
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.modules.app.repo.AppConfigRepository
import com.ifmix.core.api.entity.app.AppConfigRevision
import com.ifmix.core.api.entity.app.ConfigContent
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Component
class AppConfigAggHandler(
    private val revisionRepo: AppConfigRepository,
) {
    data class CreateRevisionInput(
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

    fun findAppIdByBundleId(mc: ModuleCtx, bundleId: String): UUID? =
        revisionRepo.findByBundleId(mc, bundleId)?.appId

    fun findAppIdByAndroidPackage(mc: ModuleCtx, packageName: String): UUID? =
        revisionRepo.findByAndroidPackage(mc, packageName)?.appId

    fun findActiveByAppId(mc: ModuleCtx, appId: UUID): AppConfigRevision? =
        revisionRepo.findActiveByAppId(mc, appId)

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
