package com.ifmix.api.core.entity.appconfig

/** 聚合 JSONB 值对象 */
data class ConfigContent(
    val apple: AppleConfigValue = AppleConfigValue(),
    val google: GoogleConfigValue = GoogleConfigValue(),
    val iap: IapConfigValue = IapConfigValue(),
    val wechat: WechatConfigValue = WechatConfigValue(),
)

/** Apple 相关配置 */
data class AppleConfigValue(
    val appAppleId: String? = null,
    val issuerId: String? = null,
    val keyId: String? = null,
    val privateKey: String? = null,
    val servicesId: String? = null,
)

data class GoogleConfigValue(
    val serviceAccount: String? = null,
    val clientIds: GoogleClientIdsValue = GoogleClientIdsValue(),
)

data class GoogleClientIdsValue(
    val ios: String? = null,
    val android: String? = null,
    val web: String? = null,
)

data class IapConfigValue(
    val productTierMap: Map<String, String> = emptyMap(),
    val env: String? = null,
)

data class WechatConfigValue(
    val appId: String? = null,
    val appSecret: String? = null,
)
