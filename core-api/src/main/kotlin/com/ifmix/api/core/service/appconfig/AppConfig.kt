package com.ifmix.api.core.service.appconfig

/**
 * 扁平配置视图（BO）：把 AppConfigDocument 的 apple/google/iap 内嵌对象摊平，
 * 供下游（iap/antique 的验证器、tier 解析等）直接读取。
 */
data class AppConfig(
    val id: String?,
    val appId: String?,
    val authTenantId: String?,
    val revision: Int,
    val appleBundleId: String?,
    val androidPackageName: String?,
    val appleAppAppleId: String?,
    val appleIssuerId: String?,
    val appleKeyId: String?,
    val applePrivateKey: String?,
    val appleServicesId: String?,
    val googleServiceAccount: String?,
    val googleClientIds: GoogleClientIds,
    val productTierMap: Map<String, String>,
    val iapEnv: String,
    val createdAt: java.time.Instant?,
    val updatedAt: java.time.Instant?,
)

/** 写入 app_config 新版本用的分组 patch（缺省字段沿用当前版本）。 */
data class AppConfigPatch(
    val authTenantId: String? = null,
    val appleBundleId: String? = null,
    val androidPackageName: String? = null,
    val apple: AppleConfig? = null,
    val google: GoogleConfig? = null,
    val iap: IapConfig? = null,
)

/** Holder for Client IDs in flattened view */
data class GoogleClientIds(
    val ios: String? = null,
    val android: String? = null,
    val web: String? = null,
)

data class AppleConfig(
    val appAppleId: String? = null,
    val issuerId: String? = null,
    val keyId: String? = null,
    val privateKey: String? = null,
    val servicesId: String? = null,
)

data class GoogleConfig(
    val serviceAccount: String? = null,
    val clientIds: GoogleClientIds = GoogleClientIds(),
)

data class IapConfig(
    val productTierMap: Map<String, String> = emptyMap(),
    val env: String? = null,
)
