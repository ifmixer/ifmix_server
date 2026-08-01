package com.ifmix.api.core.entity.ai

import org.babyfish.jimmer.sql.*
import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.SoftDeletableProps
import com.ifmix.api.core.entity.enums.AgnesKeyType
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

    /** Key 类型：PERSONAL / ENTERPRISE */
    val type: AgnesKeyType

    val rateLimit: Long
    val windowSec: Long
    val models: String?
    val unavailableUntil: Instant?
}
