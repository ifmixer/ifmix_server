package com.ifmix.api.core.common.entity

import org.babyfish.jimmer.sql.MappedSuperclass
import java.time.Instant

/** 可变实体加 updatedAt */
@MappedSuperclass
interface MutableProps : CreatedAtProps {
    val updatedAt: Instant
}
