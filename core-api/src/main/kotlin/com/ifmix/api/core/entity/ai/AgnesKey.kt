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
    override val appId: UUID

    val key: String
    val email: String?
    val type: Int
    val rateLimit: Long
    val windowSec: Long
    val models: String?
    val unavailableUntil: Instant?
}

/** AI Key 类型编码 */
object AgnesKeyType {
    const val UNKNOWN = 0
    const val PERSONAL = 100
    const val ENTERPRISE = 200

    fun fromCode(code: Int): String = when (code) {
        PERSONAL -> "PERSONAL"
        ENTERPRISE -> "ENTERPRISE"
        else -> "UNKNOWN"
    }
}
