package com.ifmix.api.core.service.appconfig

import com.ifmix.api.core.entity.appconfig.AppConfigVersion
import com.ifmix.api.core.infra.http.OperationContext
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * app_config_version 读取：按 appId / appleBundleId / androidPackageName 查 enabled=true 的当前版本，
 * 映射成扁平 [AppConfig]，内存缓存 TTL 60s。写用 [newVersion]（版本化，事务内）。
 *
 * 基于 Jimmer + PostgreSQL 实现。
 */
@Service
class AppConfigRepo(
    private val repo: com.ifmix.api.core.repository.appconfig.AppConfigRepository,
) {

    fun getByAppId(ctx: OperationContext): AppConfig? {
        val uuid = ctx.appId ?: return null
        val current = repo.findCurrentByAppId(ctx, uuid)
        return current?.let { toFlat(it) }
    }

    fun getByAppleBundleId(ctx: OperationContext, bundleId: String): AppConfig? {
        val config = repo.findByBundleId(ctx, bundleId)
        return config?.let { toFlat(it) }
    }

    fun getByAndroidPackage(ctx: OperationContext, pkg: String): AppConfig? {
        val config = repo.findByAndroidPackage(ctx, pkg)
        return config?.let { toFlat(it) }
    }

    private fun toFlat(config: AppConfigVersion): AppConfig {
        val apple = config.appleConfig
        val google = config.googleConfig
        val iap = config.iapConfig
        val wechat = config.wechatConfig
        return AppConfig(
            id = config.id.toString(),
            appId = config.appId.toString(),
            authTenantId = config.authTenantId?.toString(),
            revision = config.revision,
            appleBundleId = config.appleBundleId,
            androidPackageName = config.androidPackageName,
            appleAppAppleId = apple.appAppleId,
            appleIssuerId = apple.issuerId,
            appleKeyId = apple.keyId,
            applePrivateKey = apple.privateKey,
            appleServicesId = apple.servicesId,
            googleServiceAccount = google.serviceAccount,
            googleClientIds = GoogleClientIds(
                ios = google.clientIds.ios,
                android = google.clientIds.android,
                web = google.clientIds.web,
            ),
            productTierMap = iap.productTierMap,
            iapEnv = iap.env ?: "production",
            wechatAppId = wechat.appId,
            wechatAppSecret = wechat.appSecret,
            createdAt = config.createdAt,
        )
    }
}
