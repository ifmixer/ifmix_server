package com.ifmix.core.api.entity.common

import org.babyfish.jimmer.sql.MappedSuperclass
import java.util.UUID

@MappedSuperclass
interface ProjectScopedProps {
    val projectId: UUID
}
