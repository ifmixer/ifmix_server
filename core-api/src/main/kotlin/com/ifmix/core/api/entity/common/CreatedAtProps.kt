package com.ifmix.core.api.entity.common

import org.babyfish.jimmer.sql.MappedSuperclass
import java.time.Instant

@MappedSuperclass
interface CreatedAtProps {
    val createdAt: Instant
}
