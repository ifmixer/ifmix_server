package com.ifmix.core.api.entity.common

import org.babyfish.jimmer.sql.MappedSuperclass
import java.util.UUID

@MappedSuperclass
interface AppScopedProps {
    val appId: UUID
}
