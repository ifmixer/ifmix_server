package com.ifmix.api.core.graphql.admin

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.appconfig.AppleConfig
import com.ifmix.api.core.modules.appconfig.AppConfigMapper
import com.ifmix.api.core.modules.appconfig.AppConfigPatch
import com.ifmix.api.core.modules.appconfig.AppConfigRepo
import com.ifmix.api.core.modules.appconfig.AppConfigView
import com.ifmix.api.core.modules.appconfig.GoogleClientIds
import com.ifmix.api.core.modules.appconfig.GoogleConfig
import com.ifmix.api.core.modules.appconfig.IapConfig
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext

/**
 * GraphQL Admin AppConfig fetcher。
 * 通过 AppConfigRepo 执行所有数据操作，不直接调用 MongoTemplate。
 */
@DgsComponent
class AdminAppConfigFetcher(
    private val appConfigRepo: AppConfigRepo,
) {

    @DgsQuery(field = "appConfig_getCurrent")
    fun currentAppConfig(dfe: DgsDataFetchingEnvironment): AppConfigView? {
        val ctx = DgsContext.getCustomContext<RequestContext>(dfe)
        return appConfigRepo.getByAppId(ctx.appId)
    }

    @DgsMutation(field = "appConfig_createRevision")
    fun createAppConfigRevision(
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): AppConfigView {
        val ctx = DgsContext.getCustomContext<RequestContext>(dfe)
        val appId = input["appId"] as? String ?: ctx.appId

        @Suppress("UNCHECKED_CAST")
        val appleConfig = input["appleConfig"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val googleConfig = input["googleConfig"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val iapConfig = input["iapConfig"] as? Map<String, Any?>

        val patch = AppConfigPatch(
            authTenantId = input["authTenantId"] as? String,
            appleBundleId = input["appleBundleId"] as? String,
            androidPackageName = input["androidPackageName"] as? String,
            apple = appleConfig?.let { parseAppleConfig(it) },
            google = googleConfig?.let { parseGoogleConfig(it) },
            iap = iapConfig?.let { parseIapConfig(it) },
        )

        appConfigRepo.newVersion(ctx, appId, patch)

        return appConfigRepo.getByAppId(appId)
            ?: throw IllegalStateException("Failed to read AppConfig after creation")
    }

    @DgsMutation(field = "appConfig_toggleRevision")
    fun toggleAppConfigRevision(
        @InputArgument id: String,
        @InputArgument enabled: Boolean,
        dfe: DgsDataFetchingEnvironment,
    ): AppConfigView {
        val ctx = DgsContext.getCustomContext<RequestContext>(dfe)
        return appConfigRepo.toggleRevision(ctx, id, enabled)
            ?: throw IllegalStateException("No active AppConfigRevision found after toggle")
    }

    // ---- helpers ----

    private fun parseAppleConfig(input: Map<String, Any?>): AppleConfig = AppleConfig(
        appAppleId = input["appAppleId"] as? String,
        issuerId = input["issuerId"] as? String,
        keyId = input["keyId"] as? String,
        privateKey = input["privateKey"] as? String,
        servicesId = input["servicesId"] as? String,
    )

    private fun parseGoogleConfig(input: Map<String, Any?>): GoogleConfig {
        @Suppress("UNCHECKED_CAST")
        val rawClientIds = input["clientIds"] as? Map<String, String?>
        return GoogleConfig(
            serviceAccount = input["serviceAccount"] as? String,
            clientIds = GoogleClientIds(
                ios = rawClientIds?.get("ios"),
                android = rawClientIds?.get("android"),
                web = rawClientIds?.get("web"),
            ),
        )
    }

    private fun parseIapConfig(input: Map<String, Any?>): IapConfig {
        @Suppress("UNCHECKED_CAST")
        val productTierMap = (input["productTierMap"] as? Map<String, Any>)?.mapValues { (_, v) -> v.toString() }
        return IapConfig(
            productTierMap = productTierMap ?: emptyMap(),
            env = input["env"] as? String,
        )
    }
}
