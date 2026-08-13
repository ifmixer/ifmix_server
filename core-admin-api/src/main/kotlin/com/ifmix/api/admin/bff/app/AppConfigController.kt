package com.ifmix.api.admin.bff.app

import com.ifmix.api.core.entity.appconfig.dto.AppConfigRevisionCreateInput
import com.ifmix.api.core.entity.appconfig.dto.AppConfigRevisionDto
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.infra.dto.ToggleRevisionRequest
import com.ifmix.api.core.modules.app.service.AppConfigService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * App 管理端 BFF — AppConfigRevision 配置管理。
 */
@RestController
@RequestMapping("/app/core")
class AppConfigController(private val appConfigService: AppConfigService) {

    /**
     * 创建新的 app 配置版本。
     * 若 enabled=true，自动 disable 当前生效版本。
     */
    @PostMapping("/mutation/appConfigRevision/createOne")
    fun createOneAppConfigRevision(
        ctx: OperationContext,
        @Valid @RequestBody req: AppConfigRevisionCreateInput,
    ): AppConfigRevisionDto {
        ctx.mustGetAppId()
        return appConfigService.createOneRevision(ctx, req)
    }

    /**
     * 切换指定 revision 的启用状态。
     * enabled=true 时先 disable 所有其他生效版本，再启用目标。
     */
    @PostMapping("/mutation/appConfigRevision/toggleRevision")
    fun toggleRevision(
        ctx: OperationContext,
        @Valid @RequestBody req: ToggleRevisionRequest,
    ): AppConfigRevisionDto {
        ctx.mustGetAppId()
        return appConfigService.toggleRevision(ctx, req.id, req.enabled)
    }
}
