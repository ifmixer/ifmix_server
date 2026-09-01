package com.ifmix.api.core.entity.common

import org.babyfish.jimmer.sql.Column
import org.babyfish.jimmer.sql.MappedSuperclass
import java.util.UUID

/** Customer 归属标记：customerId 可选（匿名未转正时为 null）。 */
@MappedSuperclass
interface CustomerOwnedProps {
    @Column(name = "customer_id")
    val customerId: UUID?
}
