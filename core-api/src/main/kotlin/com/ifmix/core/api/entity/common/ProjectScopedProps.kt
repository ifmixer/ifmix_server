package com.ifmix.core.api.entity.common

import org.babyfish.jimmer.sql.MappedSuperclass

@MappedSuperclass
interface ProjectScopedProps {
    /** 逻辑外键，指向 project_info.id（slug）。跨模块不用物理 FK。 */
    val projectId: String
}
