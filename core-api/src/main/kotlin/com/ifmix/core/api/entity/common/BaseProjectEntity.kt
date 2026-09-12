package com.ifmix.core.api.entity.common

import org.babyfish.jimmer.sql.MappedSuperclass

/** App 级实体基类: UUID 主键 + projectId + createdAt + updatedAt */
@MappedSuperclass
interface BaseProjectEntity : UUIDProps, ProjectScopedProps, MutableProps
