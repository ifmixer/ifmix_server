package com.ifmix.core.api.entity.ai

import com.ifmix.core.api.entity.common.BaseProjectEntity
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * DeepResearch 任务实体。一 scan 可有多条历史版本（每次发起新建记录），
 * 权威指针在 scan_record.latest_deep_research_id（见 docs/superpowers/specs/2026-10-02 设计 §3.4）。
 * scanRecordId 为逻辑外键（跨聚合，不用 @ManyToOne）。
 * 结果存 R2（file_key）；premium_result 列仅为旧数据兼容保留，新记录恒为 null。
 */
@Entity
@Table(name = "core_ai_scan_deep_research")
interface ScanDeepResearch : BaseProjectEntity {

    @Column(name = "scan_record_id")
    val scanRecordId: UUID

    @Serialized
    @Column(name = "premium_result")
    val premiumResult: Map<String, Any?>?

    @Column(name = "prompt_version")
    val promptVersion: String

    /** 任务状态，码表见 [DeepResearchStatuses]（旧数据回填 30=SUCCESS）。 */
    val status: Int

    /** doc JSON 结构版本（与 R2 doc 顶层 docVersion 一致），供将来 doc 迁移筛选。 */
    val docVersion: Int

    /** 结果 doc 的对象存储 key（R2 bucket u2）。SUCCESS 后写入；旧数据/未成功为 null。 */
    @Column(name = "file_key")
    val fileKey: String?

    /** 稳定错误码，码表见 [DeepResearchErrorCodes]；仅 FAILED 时非空。 */
    @Column(name = "error_code")
    val errorCode: String?

    /** 结构化失败详情（AI_STATUS_REJECTED 时含 scan_status 供补拍提示）；不含内部异常栈。 */
    @Serialized
    @Column(name = "error_details")
    val errorDetails: Map<String, Any?>?
}
