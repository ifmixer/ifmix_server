package com.ifmix.api.core.entity.appconfig

import org.babyfish.jimmer.sql.*
import com.ifmix.api.core.entity.AppScopedProps
import java.time.Instant
import java.util.UUID

/**
 * per-app 配置（版本化：追加式，deletedAt 标记历史版本；至多一条当前版本）。
 */
@Entity
@Table(name = "app_config")
interface AppConfig : AppScopedProps {
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

    val revision: Int

    @LogicalDeleted("now")
    val deletedAt: Instant?

    val createdAt: Instant
    val updatedAt: Instant
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
