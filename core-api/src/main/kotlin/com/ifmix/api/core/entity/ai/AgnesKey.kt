package com.ifmix.api.core.entity.ai

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.MutableProps
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

/**
 * Agnes AI Key 实体。
 */
@Entity
@Table(name = "core_agnes_key")
interface AgnesKey : AppScopedProps, MutableProps {
    @Id
    val id: UUID

    val key: String
    val email: String?
    val type: Int
    val rateLimit: Long
    val windowSec: Long
    val models: String?
    val unavailableUntil: Instant?
}
