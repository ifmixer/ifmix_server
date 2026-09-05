package com.ifmix.core.api.entity.ai

import com.ifmix.core.api.entity.common.BaseAppEntity
import org.babyfish.jimmer.sql.*
import java.time.Instant

/** Agnes Key 类型编码。typealias（Int 全链路透传）。 */
typealias AgnesKeyType = Int

/**
 * Agnes AI Key 实体。
 */
@Entity
@Table(name = "ai_agnes_key")
interface AgnesKey : BaseAppEntity {


    val key: String
    val email: String?
    /** ponytail: 类型编码，码表未在代码中确证，暂只提供 typealias 无常量登记。 */
    val type: AgnesKeyType
    val rateLimit: Long
    val windowSec: Long
    val models: String?
    val unavailableUntil: Instant?
}
