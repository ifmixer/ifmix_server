package com.ifmix.core.api.entity.ai

import com.ifmix.core.api.entity.common.BaseAppEntity
import org.babyfish.jimmer.sql.*
import java.time.Instant

/**
 * Agnes AI Key 实体。
 */
@Entity
@Table(name = "ai_agnes_key")
interface AgnesKey : BaseAppEntity {


    val key: String
    val email: String?
    val type: Int
    val rateLimit: Long
    val windowSec: Long
    val models: String?
    val unavailableUntil: Instant?
}
