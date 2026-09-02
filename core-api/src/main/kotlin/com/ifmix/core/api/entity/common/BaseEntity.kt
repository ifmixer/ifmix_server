package com.ifmix.core.api.entity.common

import org.babyfish.jimmer.sql.MappedSuperclass

/** 全局实体基类: UUID 主键 + createdAt + updatedAt */
@MappedSuperclass
interface BaseEntity : UUIDProps, MutableProps
