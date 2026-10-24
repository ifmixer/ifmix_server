package com.ifmix.core.api.modules.project

import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ClientPlatform
import com.ifmix.core.api.modules.project.handler.CloudConfigItemAggHandler
import org.springframework.stereotype.Service

@Service
class CloudConfigItemFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: CloudConfigItemAggHandler,
) {
    /** 冷启动按 keys 拉取云配置。匿名可用：projectId/clientPlatform/otaVersion 由 ActionContext（meta）保证/携带 */
    fun getCloudConfig(
        ctx: ActionContext,
        platform: ClientPlatform?,
        otaVersion: String?,
        keys: List<String>,
    ): CloudConfigItemAggHandler.Payload =
        handler.getCloudConfig(mcFactory.forProject(ctx), platform, otaVersion, keys)
}
