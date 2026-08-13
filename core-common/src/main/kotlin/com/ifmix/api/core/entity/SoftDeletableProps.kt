package com.ifmix.api.core.entity

import org.babyfish.jimmer.sql.LogicalDeleted
import org.babyfish.jimmer.sql.MappedSuperclass
import java.time.Instant

/** 软删实体加 deletedAt */
@MappedSuperclass
interface SoftDeletableProps : MutableProps {
    @LogicalDeleted("now")
    val deletedAt: Instant?
}
