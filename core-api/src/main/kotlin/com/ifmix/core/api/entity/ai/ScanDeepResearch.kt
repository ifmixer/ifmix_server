package com.ifmix.core.api.entity.ai

import com.ifmix.core.api.entity.common.BaseProjectEntity
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * DeepResearch 结果实体。按 scanRecordId 一对一保存 premium_result。
 * scanRecordId 为逻辑外键（跨聚合，不用 @ManyToOne）。
 */
@Entity
@Table(name = "ai_scan_deep_research")
interface ScanDeepResearch : BaseProjectEntity {

    @Key
    @Column(name = "scan_record_id")
    val scanRecordId: UUID

    @Serialized
    @Column(name = "premium_result")
    val premiumResult: Map<String, Any?>?

    @Column(name = "prompt_version")
    val promptVersion: String
}
