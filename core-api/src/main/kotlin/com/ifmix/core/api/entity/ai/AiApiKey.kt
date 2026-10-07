package com.ifmix.core.api.entity.ai

import com.ifmix.core.api.entity.common.BaseEntity
import org.babyfish.jimmer.sql.*
import java.time.Instant

/** AI API Key 类型编码。typealias（Int 全链路透传），码表见 [AiApiKeyTypes]。 */
typealias AiApiKeyType = Int

/**
 * AI API Key 类型码表（个人/企业两类，步长 10）。
 * 区分依据：key 前缀 `sk-`=个人，`wk-`=企业（当前 provider=Agnes 的 key 形态）。
 */
object AiApiKeyTypes {
    /** 个人版 key（`sk-` 前缀）。 */
    const val PERSONAL: AiApiKeyType = 10
    /** 企业版 key（`wk-` 前缀）。 */
    const val ENTERPRISE: AiApiKeyType = 20
}

/**
 * AI API Key 实体。infra 级全局资源（不按 project 隔离），provider 无关的通用 key 池
 * （来源见 [ApiProviders]，当前唯一 provider 为 Agnes）。
 */
@Entity
@Table(name = "core_ai_apikey")
interface AiApiKey : BaseEntity {


    val key: String
    val email: String?
    /** 类型编码，码表见 [AiApiKeyTypes]（10=个人 / 20=企业）。 */
    val type: AiApiKeyType
    /** key 来源，码表见 [ApiProviders]（10=AGNES）。 */
    val provider: Int
    /** 是否启用。false 的 key 不参与加载/挑选（DB 列默认 true）。 */
    val enabled: Boolean
    val rateLimit: Long
    val windowSec: Long
    val models: String?
    val unavailableUntil: Instant?
}
