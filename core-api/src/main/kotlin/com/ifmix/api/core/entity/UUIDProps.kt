package com.ifmix.api.core.entity

import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.MappedSuperclass
import java.util.UUID

@MappedSuperclass
interface UUIDProps {
    @Id
    val id: UUID
}
