package com.ifmix.api.core.modules.appconfig

import com.ifmix.api.core.entity.appconfig.dto.AppConfigRevisionCreateInput
import com.ifmix.api.core.entity.appconfig.dto.AppConfigRevisionView
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.modules.appconfig.repo.AppConfigRevisionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * AppConfigRevision 写入服务。
 * 追加式版本管理：创建新 revision 行，根据 enabled 参数决定是否 toggle 旧版本。
 */
@Service
class AppConfigService(
    private val revisionRepo: AppConfigRevisionRepository,
) {

    /**
     * 创建新配置版本。
     * 若 enabled=true，自动 disable 当前生效版本（toggle）。
     */
    @Transactional
    fun createOneRevision(ctx: OperationContext, req: AppConfigRevisionCreateInput): AppConfigRevisionView {
        val appId = ctx.mustGetAppId()

        // 若新版本要生效，先 disable 当前版本
        if (req.enabled) {
            revisionRepo.disableCurrentRevisions(ctx, appId)
        }

        val created = revisionRepo.createNewRevision(ctx, req)

        return revisionRepo.findById(ctx, appId, created.id, AppConfigRevisionView::class)
            ?: throw ApiError(ErrorCode.INTERNAL, "failed to read newly created revision")
    }

    /**
     * Toggle 指定 revision 的 enabled 状态。
     * 若 toggle 为 enabled=true，先 disable 所有当前生效版本，再启用目标。
     * 若 toggle 为 enabled=false，直接 disable 目标。
     */
    @Transactional
    fun toggleRevision(ctx: OperationContext, revisionId: UUID, enabled: Boolean): AppConfigRevisionView {
        val appId = ctx.mustGetAppId()

        // 确认目标 revision 存在且属于当前 app
        revisionRepo.findById(ctx, appId, revisionId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "revision not found")

        if (enabled) {
            // 先 disable 所有当前生效版本
            revisionRepo.disableCurrentRevisions(ctx, appId)
        }

        // 更新目标 revision 的 enabled 状态
        revisionRepo.updateEnabled(ctx, revisionId, enabled)

        return revisionRepo.findById(ctx, appId, revisionId, AppConfigRevisionView::class)
            ?: throw ApiError(ErrorCode.INTERNAL, "failed to read revision after toggle")
    }
}
