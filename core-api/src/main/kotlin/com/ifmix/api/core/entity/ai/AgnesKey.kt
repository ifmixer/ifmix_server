package com.ifmix.api.core.entity.ai

import org.babyfish.jimmer.sql.*
import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.SoftDeletableProps
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "core_agnes_key")
interface AgnesKey : AppScopedProps, SoftDeletableProps {

    @Id
    val id: UUID

    override val appId: UUID

    /** API key — NOT NULL in DDL */
    val key: String

    val email: String?

    /** Key 类型编码。0=UNKNOWN, 100=PERSONAL, 200=ENTERPRISE */
    val type: Int

    val rateLimit: Long
    val windowSec: Long
    val models: String?
    val unavailableUntil: Instant?
}
