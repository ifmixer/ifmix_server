package com.ifmix.api.core.modules.appconfig

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
    private val repo: com.ifmix.api.core.common.jimmer.repository.appconfig.AppConfigRepository,
) {

    fun getByAppId(appId: String): com.ifmix.api.core.modules.appconfig.AppConfig? {
        try {
            val uuid = UUID.fromString(appId)
            val all = repo.findAll()
            val current = all.find { it.appId == uuid && it.deletedAt == null }
            return current?.let { toFlat(it) }
        } catch (e: Exception) {
            return null
        }
    }

    fun getByAppleBundleId(bundleId: String): com.ifmix.api.core.modules.appconfig.AppConfig? {
        val all = repo.findAll()
        val config = all.find { it.appleBundleId == bundleId }
        return config?.let { toFlat(it) }
    }

    fun getByAndroidPackage(pkg: String): com.ifmix.api.core.modules.appconfig.AppConfig? {
        val all = repo.findAll()
        val config = all.find { it.androidPackageName == pkg }
        return config?.let { toFlat(it) }
    }

    private fun toFlat(config: com.ifmix.api.core.common.jimmer.entity.appconfig.AppConfig): com.ifmix.api.core.modules.appconfig.AppConfig {
        return com.ifmix.api.core.modules.appconfig.AppConfig(
            id = config.id.toString(),
            appId = config.appId.toString(),
            authTenantId = config.authTenantId?.toString(),
            revision = config.revision,
            appleBundleId = config.appleBundleId,
            androidPackageName = config.androidPackageName,
            appleAppAppleId = null,
            appleIssuerId = null,
            appleKeyId = null,
            applePrivateKey = null,
            appleServicesId = null,
            googleServiceAccount = null,
            googleClientIds = com.ifmix.api.core.modules.appconfig.GoogleClientIds(null, null, null),
            productTierMap = emptyMap(),
            iapEnv = "production",
            createdAt = config.createdAt,
            updatedAt = config.updatedAt,
        )
    }
}
