package com.ifmix.api.core.service.appconfig

import com.ifmix.api.core.entity.appconfig.AppConfig as AppConfigEntity
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * app_config 读取：按 appId / appleBundleId / androidPackageName 查"当前版本"（deletedAt=null），
 * 映射成扁平 [AppConfig]，内存缓存 TTL 60s。写用 [newVersion]（版本化，事务内）。
 *
 * 基于 Jimmer + PostgreSQL 实现。
 */
@Service
class AppConfigRepo(
    private val repo: com.ifmix.api.core.repository.appconfig.AppConfigRepository,
) {

    fun getByAppId(appId: String): AppConfig? {
        return try {
            val uuid = UUID.fromString(appId)
            val current = repo.findCurrentByAppId(uuid)
            current?.let { toFlat(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun getByAppleBundleId(bundleId: String): AppConfig? {
        val config = repo.findByBundleId(bundleId)
        return config?.let { toFlat(it) }
    }

    fun getByAndroidPackage(pkg: String): AppConfig? {
        val config = repo.findByAndroidPackage(pkg)
        return config?.let { toFlat(it) }
    }

    private fun toFlat(config: AppConfigEntity): AppConfig {
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
            updatedAt = config.updatedAt,
        )
    }
}
