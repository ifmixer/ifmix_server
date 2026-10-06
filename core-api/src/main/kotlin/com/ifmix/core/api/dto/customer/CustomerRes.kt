package com.ifmix.core.api.dto.customer

import java.util.UUID

/** Customer 视图（GraphQL `type Customer` 字段级一致：id、anonymous）。 */
data class CustomerRes(
    val id: UUID,
    /** 是否匿名（未转正）。 */
    val anonymous: Boolean,
)
