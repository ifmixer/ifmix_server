package com.ifmix.api.core.entity

import org.babyfish.jimmer.sql.MappedSuperclass
import java.time.Instant

@MappedSuperclass
interface CreatedAtProps {
    val createdAt: Instant
}
