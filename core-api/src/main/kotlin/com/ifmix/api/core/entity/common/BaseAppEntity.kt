package com.ifmix.api.core.entity.common

import org.babyfish.jimmer.sql.MappedSuperclass

/** App 级实体基类: UUID 主键 + appId + createdAt + updatedAt */
@MappedSuperclass
interface BaseAppEntity : UUIDProps, AppScopedProps, MutableProps
