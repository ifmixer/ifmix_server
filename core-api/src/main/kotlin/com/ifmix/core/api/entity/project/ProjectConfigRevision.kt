package com.ifmix.core.api.entity.project

import com.ifmix.core.api.entity.common.ProjectScopedProps
import com.ifmix.core.api.entity.common.UUIDProps
import com.ifmix.core.api.entity.common.CreatedAtProps
import org.babyfish.jimmer.sql.*

/**
 * per-app 配置版本。追加式；enabled=true 的为当前生效版本。
 */
@Entity
@Table(name = "project_config_revision")
interface ProjectConfigRevision : UUIDProps, ProjectScopedProps, CreatedAtProps {
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
