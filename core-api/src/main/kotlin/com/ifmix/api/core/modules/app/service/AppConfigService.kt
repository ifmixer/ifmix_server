package com.ifmix.api.core.modules.app.service

import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.modules.app.repo.AppConfigRepository
import com.ifmix.api.core.model.AppConfigRevision
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

/**
 * App 配置管理 Service（jOOQ 版本）。
 */
@Service
class AppConfigService(
    private val revisionRepo: AppConfigRepository,
) {

    /** 创建配置版本时的请求输入 */
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

    /** 配置版本响应 */
    data class RevisionDto(
        val id: UUID,
        val createdAt: java.time.Instant,
        val appId: UUID,
        val authTenantId: UUID? = null,
        val appleBundleId: String? = null,
        val androidPackageName: String? = null,
        val content: String,
        val revisionNumber: Int,
        val enabled: Boolean,
        val slug: String,
        val note: String,
    )

    private val mapper = jacksonObjectMapper()

    @Transactional
    fun createOneRevision(ctx: OperationContext, req: CreateRevisionInput): RevisionDto {
        val appId = ctx.mustGetAppId()

        if (req.enabled) {
            revisionRepo.disableCurrentRevisions(ctx.repoCtx, appId)
        }

        val now = java.time.Instant.now()
        val revision = AppConfigRevision(
            id = UuidV7.generate(),
            appId = appId,
            authTenantId = req.authTenantId,
            appleBundleId = req.appleBundleId,
            androidPackageName = req.androidPackageName,
            revisionNumber = req.revisionNumber,
            createdAt = now,
            enabled = req.enabled,
            slug = req.slug,
            content = req.content.toString(),
            note = req.note,
        )

        val created = revisionRepo.insert(ctx.repoCtx, revision)
        return toDto(created)
    }

    @Transactional
    fun toggleRevision(ctx: OperationContext, revisionId: UUID, enabled: Boolean): RevisionDto {
        val appId = ctx.mustGetAppId()

        val existing = revisionRepo.findById(ctx.repoCtx, revisionId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "revision not found")

        // 校验归属：revision 必须属于当前 app
        if (existing.appId != appId) {
            throw ApiError(ErrorCode.NOT_FOUND, "revision not found")
        }

        if (enabled) {
            revisionRepo.disableCurrentRevisions(ctx.repoCtx, appId)
        }

        revisionRepo.updateEnabled(ctx.repoCtx, revisionId, enabled)

        val updated = revisionRepo.findById(ctx.repoCtx, revisionId)
            ?: throw ApiError(ErrorCode.INTERNAL, "failed to read revision after toggle")

        return toDto(updated)
    }

    private fun toDto(model: AppConfigRevision): RevisionDto {
        return RevisionDto(
            id = model.id,
            createdAt = model.createdAt,
            appId = model.appId,
            authTenantId = model.authTenantId,
            appleBundleId = model.appleBundleId,
            androidPackageName = model.androidPackageName,
            content = model.content,
            revisionNumber = model.revisionNumber,
            enabled = model.enabled,
            slug = model.slug,
            note = model.note,
        )
    }
}
