package com.ifmix.api.core.entity.appconfig

import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

/**
 * 全局应用注册表（不按 appId 分片，id 即 appId）。
 */
@Entity
@Table(name = "app_info")
interface AppInfo {
    @Id
    val id: UUID

    val name: String?
    val description: String?
    val slug: String?

    val createdAt: Instant
    val updatedAt: Instant}
