package com.ifmix.api.core.modules.app.service

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.app.repo.AppConfigRepository
import com.ifmix.api.core.model.app.AppConfigRevision
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
class AppConfigFacadeService(
    private val revisionRepo: AppConfigRepository,
) {
    data class CreateRevisionInput(
        val authTenantId: UUID? = null,
        val appleBundleId: String? = null,
        val androidPackageName: String? = null,
        val content: Map<String, Any?>,
        val revisionNumber: Int,
        val enabled: Boolean = false,
        val slug: String,
        val note: String,
    )

    data class RevisionDto(
        val id: UUID, val createdAt: java.time.Instant, val appId: UUID,
        val authTenantId: UUID? = null, val appleBundleId: String? = null,
        val androidPackageName: String? = null, val content: String,
        val revisionNumber: Int, val enabled: Boolean, val slug: String, val note: String,
    )

    private val mapper = jacksonObjectMapper()
    private fun svc(opCtx: OperationContext) = SvcCtx(op = opCtx, dsl = SvcCtx.DEFAULT.dsl)

    @Transactional
    fun createOneRevision(ctx: OperationContext, req: CreateRevisionInput): RevisionDto {
        val appId = ctx.appId!!
        if (req.enabled) revisionRepo.disableCurrentRevisions(svc(ctx), appId)
        val now = java.time.Instant.now()
        val revision = AppConfigRevision(id = UuidV7.generate(), appId = appId,
            authTenantId = req.authTenantId, appleBundleId = req.appleBundleId,
            androidPackageName = req.androidPackageName, revisionNumber = req.revisionNumber,
            createdAt = now, enabled = req.enabled, slug = req.slug,
            content = req.content.toString(), note = req.note)
        return toDto(revisionRepo.insert(svc(ctx), revision))
    }

    @Transactional
    fun toggleRevision(ctx: OperationContext, revisionId: UUID, enabled: Boolean): RevisionDto {
        val appId = ctx.appId!!
        val sc = svc(ctx)
        val existing = revisionRepo.findById(sc, revisionId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "revision not found")
        if (existing.appId != appId) throw ApiError(ErrorCode.NOT_FOUND, "revision not found")
        if (enabled) revisionRepo.disableCurrentRevisions(sc, appId)
        revisionRepo.updateEnabled(sc, revisionId, enabled)
        val updated = revisionRepo.findById(sc, revisionId)
            ?: throw ApiError(ErrorCode.INTERNAL, "failed to read revision after toggle")
        return toDto(updated)
    }

    private fun toDto(m: AppConfigRevision): RevisionDto = RevisionDto(
        id = m.id, createdAt = m.createdAt, appId = m.appId,
        authTenantId = m.authTenantId, appleBundleId = m.appleBundleId,
        androidPackageName = m.androidPackageName, content = m.content,
        revisionNumber = m.revisionNumber, enabled = m.enabled,
        slug = m.slug, note = m.note,
    )
}
