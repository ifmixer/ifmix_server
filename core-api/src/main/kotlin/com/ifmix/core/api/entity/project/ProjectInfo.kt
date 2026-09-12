package com.ifmix.core.api.entity.project

import com.ifmix.core.api.entity.common.BaseEntity
import org.babyfish.jimmer.sql.*

/**
 * 全局应用注册表（不按 projectId 分片，id 即 projectId）。
 */
@Entity
@Table(name = "project_info")
interface ProjectInfo : BaseEntity {

    val name: String?
    val description: String?
    val slug: String
}
