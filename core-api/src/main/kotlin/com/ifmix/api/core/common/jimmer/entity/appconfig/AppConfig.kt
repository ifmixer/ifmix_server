package com.ifmix.api.core.common.jimmer.entity.appconfig

import org.babyfish.jimmer.sql.*
import com.ifmix.api.core.common.jimmer.entity.AppScopedProps
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

    val appleConfig: String?
    val googleConfig: String?
    val iapConfig: String?

    val revision: Int

    @LogicalDeleted("now")
    val deletedAt: Instant?

    val createdAt: Instant
    val updatedAt: Instant}
