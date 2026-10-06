package com.ifmix.core.api.dto.ai

import java.util.UUID

/**
 * ai 模块 RPC 协议出参（手写，M3 去 generated types）：字段与 ai.graphqls 对应 GraphQL
 * output 类型一致（客户端响应形状不变），DateTime 统一 [Instant.toString] ISO-8601 UTC 字符串
 *（与 GraphQL DateTime 标量序列化一致，demo 同款决策）。
 *
 * 对照表：
 * - [ScanRecordRes] ← ScanRecord（详情视图，含 basicResult / latestDeepResearch）
 * - [ScanRecordListRes] ← ScanRecordListView（列表视图，无 latestDeepSearch/hasDeepSearch；
 *   basicResult 字段保留 schema 形状但恒 null——repo 列表视图不加载 JSONB 大字段）
 * - [ScanCollectionItemRes] ← ScanCollectionItem（scanRecord 为详情形状；组装走批量，
 *   basicResult 恒 null——对齐 ScanRecordsDataLoader 的 findByIdsListView 用法）
 */
data class ImageRefRes(val key: String, val category: Int?)

data class ScanDeepResearchRes(
    val id: UUID,
    val scanRecordId: UUID,
    /** 高级扫描结果（JSONB，直接来自 PG premium_result 列） */
    val premiumResult: Map<String, Any?>?,
    val createdAt: String,
    val updatedAt: String?,
)

data class ScanRecordRes(
    val id: UUID,
    val createdAt: String,
    val updatedAt: String?,
    val isPublic: Boolean,
    /** 扫描任务状态。10=CREATED, 20=IN_PROGRESS, 30=SUCCESS, 40=FAILED */
    val status: Int,
    val errorCode: String?,
    val locale: String?,
    val country: String?,
    val currency: String?,
    val userDisplayName: String?,
    val userNotes: String?,
    val collected: Boolean,
    val hasDeepSearch: Boolean,
    val images: List<ImageRefRes>?,
    /** 基础扫描结果（JSONB） */
    val basicResult: Map<String, Any?>?,
    /** 当前有效（最新且成功）的 deep research，按 latest_deep_research_id 权威指针加载 */
    val latestDeepResearch: ScanDeepResearchRes?,
)

data class ScanRecordListRes(
    val id: UUID,
    val images: List<ImageRefRes>?,
    /** 扫描任务状态。10=CREATED, 20=IN_PROGRESS, 30=SUCCESS, 40=FAILED */
    val status: Int,
    val errorCode: String?,
    /** schema 形状保留；列表视图不加载 JSONB 大字段，恒 null */
    val basicResult: Map<String, Any?>?,
    val locale: String?,
    val country: String?,
    val currency: String?,
    val userDisplayName: String?,
    val userNotes: String?,
    val collected: Boolean,
    val isPublic: Boolean,
    val createdAt: String,
    val updatedAt: String?,
)

data class ScanStatusRes(val scanId: UUID, val status: Int, val errorCode: String?)

data class CreateScanRes(
    val scanId: UUID,
    /** 20=IN_PROGRESS，executor 提交失败时为 40 */
    val status: Int,
    /** FAILED 时返回技术错误码 */
    val errorCode: String?,
)

data class UpdateScanRes(val success: Boolean, val scanRecord: ScanRecordRes?)

data class BatchUpdateScanRes(
    /** 实际更新的记录数（不属于调用者的 id 不计入） */
    val updatedCount: Int,
)

data class DeleteScanRes(val success: Boolean)

data class RunDeepResearchRes(
    /** 新建的 deep research 任务 id，前端据此轮询 */
    val deepResearchId: UUID,
    /** 20=IN_PROGRESS；executor 提交失败时直接返回 40 */
    val status: Int,
    /** status=40 时返回：稳定错误码（TASK_SUBMISSION_FAILED）；IN_PROGRESS 时为 null */
    val errorCode: String?,
)

data class DeepResearchStatusRes(
    val deepResearchId: UUID,
    /** 20=IN_PROGRESS, 30=SUCCESS, 40=FAILED */
    val status: Int,
    /** FAILED 时返回：稳定错误码（AI_FAILED/TIMEOUT/TASK_SUBMISSION_FAILED/INTERNAL_ERROR） */
    val errorCode: String?,
)

data class ScanCollectionRes(val id: UUID, val isDefault: Boolean, val createdAt: String)

data class ScanCollectionItemRes(
    val id: UUID,
    val scanRecord: ScanRecordRes,
    val createdAt: String,
)

data class AddCollectionItemRes(val collectionId: UUID, val alreadyExists: Boolean)

data class RemoveCollectionItemsRes(val removedCount: Int)
