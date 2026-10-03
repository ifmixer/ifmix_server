package com.ifmix.core.api.entity.ai

import com.ifmix.core.api.entity.common.BaseProjectEntity
import com.ifmix.core.api.entity.common.CustomerIdProps
import com.ifmix.core.api.entity.common.InstallIdProps
import com.ifmix.core.api.entity.common.SoftDeletableProps
import com.ifmix.core.api.entity.common.UserPreferenceProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/** 扫描状态编码。typealias（Int 全链路透传），码表见 [ScanStatuses]。 */
typealias ScanStatus = Int

/**
 * 扫描任务技术状态码表（0 保留，从 10 起步长 10）。
 * AI 业务结论保存在 basicResult.scan_status，不得与任务状态混用。
 */
object ScanStatuses {
    const val CREATED: ScanStatus = 10
    const val IN_PROGRESS: ScanStatus = 20
    const val SUCCESS: ScanStatus = 30
    const val FAILED: ScanStatus = 40
    /** 兼容旧调用方编译；异步 scan 新流程不使用该别名。 */
    const val READY: ScanStatus = SUCCESS
}

@Entity
@Table(name = "core_ai_scan_record")
interface ScanRecord : BaseProjectEntity, SoftDeletableProps, CustomerIdProps, InstallIdProps, UserPreferenceProps {

    @Serialized
    @Column(name = "image_keys")
    val images: List<ImageRef>

    @Serialized
    @Column(name = "basic_result")
    val basicResult: Map<String, Any?>?

    /**
     * 权威指针：指向当前有效（最新且成功）的 deep research 任务。
     * 逻辑外键（跨聚合，不用 @ManyToOne）；null = 从未成功过。
     * latest 判定与并发规则见 docs/superpowers/specs/2026-10-03-deep-research-async-pg-versioning-design.md §3.4。
     */
    @Column(name = "latest_deep_research_id")
    val latestDeepResearchId: UUID?

    val status: ScanStatus

    /** 技术失败稳定码；任务 SUCCESS 时为空。 */
    @Column(name = "error_code")
    val errorCode: String?

    /** 技术失败结构化详情，不含异常栈。 */
    @Serialized
    @Column(name = "error_details")
    val errorDetails: Map<String, Any?>?

    val clientIp: String?
    val userDisplayName: String?
    val userNotes: String?
    val collected: Boolean
    @Column(name = "is_public")
    val isPublic: Boolean
    @Column(name = "has_deep_search")
    val hasDeepSearch: Boolean
    @Column(name = "prompt_version")
    val promptVersion: String
}
