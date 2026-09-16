package com.ifmix.core.api.entity.project

import com.ifmix.core.api.entity.common.MutableProps
import com.ifmix.core.api.entity.common.StringIdProps
import org.babyfish.jimmer.sql.*

/**
 * 全局应用注册表（不按 projectId 分片）。
 * 主键 id 即 slug（对外可读、创建后不可变、格式受控），业务表的 projectId 逻辑外键指向它。
 */
@Entity
@Table(name = "project_info")
interface ProjectInfo : StringIdProps, MutableProps {

    val name: String?
    val description: String?
}
