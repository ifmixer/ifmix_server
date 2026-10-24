package com.ifmix.core.api.bff.graphql.customer.project

import com.ifmix.core.api.generated.types.CloudConfigResult
import com.ifmix.core.api.infra.graphql.ActionContextProvider
import com.ifmix.core.api.modules.project.CloudConfigItemFacade
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument

/**
 * Cloud Config（project 模块）GraphQL DataFetcher。
 * 冷启动高频读：纯查询无事务（不走 GlobalTxRunner）；匿名可用（requireActorType=null）。
 */
@DgsComponent
class ProjectFetcher(
    private val cloudConfigFacade: CloudConfigItemFacade,
    private val ctxProvider: ActionContextProvider,
) {
    @DgsQuery(field = "q_project_cloudConfig_get")
    fun cloudConfig(dfe: DgsDataFetchingEnvironment, @InputArgument keys: List<String>): CloudConfigResult {
        val ctx = ctxProvider.fromDfe(dfe, requireActorType = null)
        val payload = cloudConfigFacade.getCloudConfig(ctx, ctx.clientPlatform, ctx.otaVersion, keys)
        return CloudConfigResult(configVersion = payload.configVersion, values = payload.values)
    }
}
