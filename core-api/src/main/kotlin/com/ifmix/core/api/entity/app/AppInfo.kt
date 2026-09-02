package com.ifmix.core.api.entity.app

import com.ifmix.core.api.entity.common.BaseEntity
import org.babyfish.jimmer.sql.*

/**
 * 全局应用注册表（不按 appId 分片，id 即 appId）。
 */
@Entity
@Table(name = "app_info")
interface AppInfo : BaseEntity {

    val name: String?
    val description: String?
    val slug: String
}
