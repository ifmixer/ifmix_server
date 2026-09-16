package com.ifmix.core.api.modules.project.handler

import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.UuidV7
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.modules.project.repo.ProjectConfigRepository
import com.ifmix.core.api.entity.project.ProjectConfigRevision
import com.ifmix.core.api.entity.project.ConfigContent
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Component
class ProjectConfigAggHandler(
    private val revisionRepo: ProjectConfigRepository,
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

    fun createOneRevision(mc: ModuleCtx, req: CreateRevisionInput): ProjectConfigRevision {
        val projectId = mc.projectId!!
        if (req.enabled) revisionRepo.disableCurrentRevisions(mc, projectId)
        val now = java.time.Instant.now()
        val revision = ProjectConfigRevision {
            id = UuidV7.generate()
            this.projectId = projectId
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

    fun findAppIdByBundleId(mc: ModuleCtx, bundleId: String): String? =
        revisionRepo.findByBundleId(mc, bundleId)?.projectId

    fun findAppIdByAndroidPackage(mc: ModuleCtx, packageName: String): String? =
        revisionRepo.findByAndroidPackage(mc, packageName)?.projectId

    fun findActiveByAppId(mc: ModuleCtx, projectId: String): ProjectConfigRevision? =
        revisionRepo.findActiveByAppId(mc, projectId)

    fun toggleRevision(mc: ModuleCtx, revisionId: UUID, enabled: Boolean): ProjectConfigRevision {
        val projectId = mc.projectId!!
        val existing = revisionRepo.findById(mc, projectId, revisionId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "revision not found")
        if (existing.projectId != projectId) throw ApiError(ErrorCode.NOT_FOUND, "revision not found")
        if (enabled) revisionRepo.disableCurrentRevisions(mc, projectId)
        revisionRepo.updateEnabled(mc, revisionId, enabled)
        val updated = revisionRepo.findById(mc, projectId, revisionId)
            ?: throw ApiError(ErrorCode.INTERNAL, "failed to read revision after toggle")
        return updated
    }

}
