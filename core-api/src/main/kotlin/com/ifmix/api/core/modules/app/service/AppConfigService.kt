package com.ifmix.api.core.modules.app.service

import com.ifmix.api.core.entity.appconfig.ConfigContent
import com.ifmix.api.core.entity.appconfig.dto.AppConfigRevisionCreateInput
import com.ifmix.api.core.entity.appconfig.dto.AppConfigRevisionDto
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.modules.app.repo.AppConfigRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

/**
 * App 配置管理 Service（jOOQ 版本）。
 *
 * 保留原有的 AppConfigRevisionRepository（Jimmer 版）不动，
 * 本类作为迁移后的新版本，由 Spring 组件扫描自动注入到 AppConfigController。
 */
@Service
class AppConfigService(
    private val revisionRepo: AppConfigRepository,
) {

    private val mapper = jacksonObjectMapper()

    @Transactional
    fun createOneRevision(ctx: OperationContext, req: AppConfigRevisionCreateInput): AppConfigRevisionDto {
        val appId = ctx.mustGetAppId()

        if (req.enabled) {
            revisionRepo.disableCurrentRevisions(ctx.repoCtx, appId)
        }

        val now = java.time.Instant.now()
        val revision = com.ifmix.api.core.model.AppConfigRevision(
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
    fun toggleRevision(ctx: OperationContext, revisionId: UUID, enabled: Boolean): AppConfigRevisionDto {
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

    /** 将 jOOQ 模型转换为 Jimmer 生成的 DTO（供 REST 控制器使用） */
    private fun toDto(model: com.ifmix.api.core.model.AppConfigRevision): AppConfigRevisionDto {
        return AppConfigRevisionDto(
            id = model.id,
            createdAt = model.createdAt,
            appId = model.appId,
            authTenantId = model.authTenantId,
            appleBundleId = model.appleBundleId,
            androidPackageName = model.androidPackageName,
            content = mapper.readValue(model.content, ConfigContent::class.java),
            revisionNumber = model.revisionNumber,
            enabled = model.enabled,
            slug = model.slug,
            note = model.note,
        )
    }
}
