package com.ifmix.api.core.common.entity

import org.babyfish.jimmer.sql.MappedSuperclass
import java.time.Instant

/** 所有实体必有 createdAt */
@MappedSuperclass
interface CreatedAtProps {
    val createdAt: Instant
}
