package com.ifmix.api.core.common.entity.appconfig

import org.babyfish.jimmer.sql.*
import com.ifmix.api.core.common.entity.MutableProps
import java.util.UUID

/**
 * 全局应用注册表（不按 appId 分片，id 即 appId）。
 */
@Entity
@Table(name = "core_app_info")
interface AppInfo : MutableProps {
    @Id
    val id: UUID

    val name: String?
    val description: String?
    val slug: String
}
