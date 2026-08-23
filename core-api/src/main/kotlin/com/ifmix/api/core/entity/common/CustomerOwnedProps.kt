package com.ifmix.api.core.entity.common

import org.babyfish.jimmer.sql.MappedSuperclass
import java.util.UUID

/** Customer 归属标记：installId 必填，userId 可选（未登录时为 null）。 */
@MappedSuperclass
interface CustomerOwnedProps {
    val installId: UUID
    val userId: UUID?
}
