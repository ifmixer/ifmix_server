package com.ifmix.api.core.entity.appconfig

import org.babyfish.jimmer.sql.*
import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.CreatedAtProps
import java.util.UUID

/**
 * per-app 配置版本。追加式；enabled=true 的为当前生效版本。
 */
@Entity
@Table(name = "core_app_config_version")
interface AppConfigVersion : AppScopedProps, CreatedAtProps {
    @Id
    val id: UUID

    override val appId: UUID

    val authTenantId: UUID?
    val appleBundleId: String?
    val androidPackageName: String?

    /** JSONB — Apple 相关配置 */
    @Serialized
    val appleConfig: AppleConfigValue

    /** JSONB — Google 相关配置 */
    @Serialized
    val googleConfig: GoogleConfigValue

    /** JSONB — IAP 相关配置 */
    @Serialized
    val iapConfig: IapConfigValue

    /** JSONB — 微信开放平台配置 */
    @Serialized
    val wechatConfig: WechatConfigValue

    val revision: Int

    /** 是否为当前生效版本 */
    val enabled: Boolean

    /** 配置标识符 */
    val slug: String
}

/** JSONB 内嵌值对象 */
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
