package com.ifmix.api.core.entity.app

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.CreatedAtProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * per-app 配置版本。追加式；enabled=true 的为当前生效版本。
 */
@Entity
@Table(name = "core_app_config_revision")
interface AppConfigRevision : AppScopedProps, CreatedAtProps {
    @Id
    val id: UUID


    val authTenantId: UUID?
    val appleBundleId: String?
    val androidPackageName: String?

    /** JSONB, 整个字段替换 — 所有平台配置聚合 */
    @Serialized
    val content: ConfigContent

    val revisionNumber: Int

    /** 是否为当前生效版本 */
    val enabled: Boolean

    /** 配置标识符 */
    val slug: String

    /** 版本备注，创建时必填 */
    val note: String
}
