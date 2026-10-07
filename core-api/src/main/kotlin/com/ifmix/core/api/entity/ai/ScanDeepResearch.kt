package com.ifmix.core.api.entity.ai

import com.ifmix.core.api.entity.common.BaseProjectEntity
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * DeepResearch 任务实体。一 scan 可有多条历史版本（每次发起新建记录），
 * 权威指针在 scan_record.latest_deep_research_id（见 docs/design/ai/deep-research-async.md 设计 §3.4）。
 * scanRecordId 为逻辑外键（跨聚合，不用 @ManyToOne）。
 * 结果（premium_result JSONB）直接存 PG；历史版本长期保留，归档策略见设计 §1.1（本期不做）。
 */
@Entity
@Table(name = "core_ai_deepresearch")
interface ScanDeepResearch : BaseProjectEntity {

    @Column(name = "scan_record_id")
    val scanRecordId: UUID

    @Serialized
    @Column(name = "premium_result")
    val premiumResult: Map<String, Any?>?

    /** 本次 deep research 成功时的 basicResult 快照（统计分析用；每条历史记录各存自己那次）。 */
    @Serialized
    @Column(name = "basic_result")
    val basicResult: Map<String, Any?>?

    @Column(name = "prompt_version")
    val promptVersion: String

    /** 任务状态，码表见 [DeepResearchStatuses]（旧数据回填 30=SUCCESS）。 */
    val status: Int

    /** 稳定错误码，码表见 [AiTaskErrorCodes]；仅 FAILED 时非空。 */
    @Column(name = "error_code")
    val errorCode: String?

    /** 结构化技术失败详情；不含内部异常栈，AI 业务 status 保存在 basic_result。 */
    @Serialized
    @Column(name = "error_details")
    val errorDetails: Map<String, Any?>?
}
