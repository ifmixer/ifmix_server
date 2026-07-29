package com.ifmix.api.core.common.jimmer.entity.ai

import org.babyfish.jimmer.sql.*
import com.ifmix.api.core.common.jimmer.entity.AppScopedProps
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "agnes_key")
interface AgnesKey : AppScopedProps {

    @Id
    val id: UUID

    override val appId: UUID

    /** API key — NOT NULL in DDL */
    val key: String

    val email: String?
    val type: String?  // PRIMARY / FALLBACK / HOTSPARE
    val rateLimit: Long
    val windowSec: Long
    val models: String?
    val unavailableUntil: Instant?

    @LogicalDeleted("now")
    val deletedAt: Instant?

    val createdAt: Instant
    val updatedAt: Instant
}
