package com.ifmix.api.core.modules.appconfig

import com.ifmix.api.core.common.db.CRUDAppDocument
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/**
 * per-app 配置（版本化：追加式，deletedAt 标记历史版本；至多一条当前版本）。
 * 配置内容收进 apple/google/iap 三个内嵌对象；仅查询键/关系键保留为顶层字段。
 * 分片键 appId；partial unique (appId) where deletedAt=null 保证至多一条当前版本。
 */
@Document(collection = "app_config")
@CompoundIndex(
    name = "app_config_appid_current_uq",
    def = "{'appId': 1}",
    unique = true,
    partialFilter = "{ 'deletedAt': null }",
)
class AppConfigDocument : CRUDAppDocument() {
    var authTenantId: String? = null

    @Indexed
    var appleBundleId: String? = null

    @Indexed
    var androidPackageName: String? = null

    var apple: AppleConfig = AppleConfig()
    var google: GoogleConfig = GoogleConfig()
    var iap: IapConfig = IapConfig()

    var configVersion: Int = 1
}

/** 内嵌：Apple 商店/凭证配置。 */
data class AppleConfig(
    val appAppleId: String? = null,
    val issuerId: String? = null,
    val keyId: String? = null,
    val privateKey: String? = null,
    val servicesId: String? = null,
)

/** 内嵌：Google 商店/凭证配置。serviceAccount 存 JSON 字符串。 */
data class GoogleConfig(
    val serviceAccount: String? = null,
    val clientIds: GoogleClientIds = GoogleClientIds(),
)

data class GoogleClientIds(
    val ios: String? = null,
    val android: String? = null,
    val web: String? = null,
)

/** 内嵌：IAP 配置。 */
data class IapConfig(
    val productTierMap: Map<String, String> = emptyMap(),
    val env: String? = null,
)
