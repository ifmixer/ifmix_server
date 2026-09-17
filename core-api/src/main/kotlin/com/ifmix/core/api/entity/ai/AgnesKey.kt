package com.ifmix.core.api.entity.ai

import com.ifmix.core.api.entity.common.BaseEntity
import org.babyfish.jimmer.sql.*
import java.time.Instant

/** Agnes Key 类型编码。typealias（Int 全链路透传），码表见 [AgnesKeyTypes]。 */
typealias AgnesKeyType = Int

/** Agnes Key 类型码表（个人/企业两类，步长 10）。区分依据：key 前缀 `sk-`=个人，`wk-`=企业。 */
object AgnesKeyTypes {
    /** 个人版 key（`sk-` 前缀）。 */
    const val PERSONAL: AgnesKeyType = 10
    /** 企业版 key（`wk-` 前缀）。 */
    const val ENTERPRISE: AgnesKeyType = 20
}

/**
 * Agnes AI Key 实体。infra 级全局资源（不按 project 隔离）。
 */
@Entity
@Table(name = "ai_agnes_key")
interface AgnesKey : BaseEntity {


    val key: String
    val email: String?
    /** 类型编码，码表见 [AgnesKeyTypes]（10=个人 / 20=企业）。 */
    val type: AgnesKeyType
    /** 是否启用。false 的 key 不参与加载/挑选（DB 列默认 true）。 */
    val enabled: Boolean
    val rateLimit: Long
    val windowSec: Long
    val models: String?
    val unavailableUntil: Instant?
}
