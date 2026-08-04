package com.ifmix.api.core.entity.appconfig

import org.babyfish.jimmer.sql.*
import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.CreatedAtProps
import java.util.UUID

/**
 * per-app 配置版本。追加式；enabled=true 的为当前生效版本。
 */
@Entity
@Table(name = "core_app_config_revision")
interface AppConfigRevision : AppScopedProps, CreatedAtProps {
    @Id
    val id: UUID

    override val appId: UUID

    val authTenantId: UUID?
    val appleBundleId: String?
    val androidPackageName: String?

    /** JSONB — 所有平台配置聚合 */
    @Serialized
    val content: ConfigContent

    val revisionNumber: Int

    /** 是否为当前生效版本 */
    val enabled: Boolean

    /** 配置标识符 */
    val slug: String
}

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
