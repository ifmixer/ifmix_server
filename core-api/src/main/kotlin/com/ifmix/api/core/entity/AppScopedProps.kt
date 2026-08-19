package com.ifmix.api.core.entity

import org.babyfish.jimmer.sql.MappedSuperclass
import java.util.UUID

@MappedSuperclass
interface AppScopedProps {
    val appId: UUID
}
