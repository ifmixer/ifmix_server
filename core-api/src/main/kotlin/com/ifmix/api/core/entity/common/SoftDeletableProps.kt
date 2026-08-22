package com.ifmix.api.core.entity.common

import org.babyfish.jimmer.sql.LogicalDeleted
import org.babyfish.jimmer.sql.MappedSuperclass
import java.time.Instant

@MappedSuperclass
interface SoftDeletableProps : MutableProps {
    @LogicalDeleted("now")
    val deletedAt: Instant?
}
