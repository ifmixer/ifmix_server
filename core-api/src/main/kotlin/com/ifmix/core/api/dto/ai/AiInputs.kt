package com.ifmix.core.api.dto.ai

import com.ifmix.core.api.dto.common.CommonFindOptions
import java.util.UUID

/**
 * ai 模块 RPC 协议入参（手写，M3 去 generated types）：字段名与 GraphQL argument /
 * input 类型一一对应（客户端把 variables 原样搬进 input 段），出参见 [AiViews.kt]。
 *
 * 对照 resources/schema/customer/ai.graphqls；与 generated 版本的唯一差异：
 * - `unset` 由 generated 枚举 ScanUnsetField 改为协议字符串（大写枚举名，demo 同款做法），
 *   非法值在 handler 入口经 [requireScanUnsetFields] 抛 INVALID_REQUEST；
 * - DateTime 不出现在任何 ai 入参中（无需 Instant/String 转换决策）。
 */

// ==================== Scan ====================

data class NewScanInput(
    val images: List<NewScanImageInput>,
    /** 是否创建时即收藏（客户端「自动收藏」开关开启时传 true）；省略默认 false */
    val collected: Boolean? = null,
    val featureFlags: ScanFeatureFlagsInput? = null,
)

data class NewScanImageInput(
    val imageKey: String,
    /** 图片分类 Int 码（0=MAIN，缺省服务端按主图 0 入库），码表见 docs/guide/DATABASE.md */
    val category: Int? = null,
    val mediaType: String? = null,
)

data class ScanFeatureFlagsInput(
    /** scan 结果 push 是否启用；省略或 false 时后端不发 push */
    val scanResultPushEnabled: Boolean? = null,
)

data class UpdateScanInput(
    val id: UUID,
    val set: UpdateScanSetInput? = null,
    /** 协议字符串（原 ScanUnsetField 枚举名）；非法值 → INVALID_REQUEST */
    val unset: List<String>? = null,
)

data class UpdateScanSetInput(
    val userDisplayName: String? = null,
    val userNotes: String? = null,
    val collected: Boolean? = null,
    val isPublic: Boolean? = null,
)

/** UpdateScanInput.unset 的合法值（原 generated ScanUnsetField 枚举名）。 */
val SCAN_UNSET_FIELDS = setOf("USER_DISPLAY_NAME", "USER_NOTES")

/** 非法 unset 字段抛 INVALID_REQUEST（handler 入口调用；纯函数便于单测，仿 dto/demo requireUnsetFields）。 */
fun requireScanUnsetFields(unset: List<String>?, allowed: Set<String> = SCAN_UNSET_FIELDS) {
    unset?.find { it !in allowed }?.let {
        throw com.ifmix.core.api.infra.http.ApiError(
            com.ifmix.core.api.infra.http.ErrorCode.INVALID_REQUEST, "invalid unset field: $it",
        )
    }
}

/** 批量更新 scan（当前主要用于批量设置 collected）。仅影响调用者本人拥有的记录。 */
data class BatchUpdateScanInput(
    /** 要更新的 scan id 列表（仅本人拥有的会被更新） */
    val ids: List<UUID>,
    val set: BatchUpdateScanSetInput,
)

data class BatchUpdateScanSetInput(
    val collected: Boolean? = null,
    val isPublic: Boolean? = null,
)

data class RunDeepResearchInput(
    val scanRecordId: UUID,
    val images: List<DeepResearchImageInput>,
    val featureFlags: DeepResearchFeatureFlagsInput? = null,
)

data class DeepResearchImageInput(
    val imageKey: String,
    /** 图片分类 Int 码（0=MAIN），码表见 docs/guide/DATABASE.md */
    val category: Int? = null,
    val mediaType: String? = null,
)

data class DeepResearchFeatureFlagsInput(
    /** deep research push 是否启用；省略或 false 时后端不发 push */
    val deepResearchPushEnabled: Boolean? = null,
)

// ==================== 查询入参 ====================

/** q_ai_scan_getById（旧 q_ai_findMyScanById(id)） */
data class FindScanByIdInput(val id: UUID)

/** q_ai_scan_getStatus（旧 q_ai_getScanStatus(scanId)） */
data class GetScanStatusInput(val scanId: UUID)

/** q_ai_scan_list（旧 q_ai_findMyScans(findOptions)）：CommonFindOptions 直接吃 dto/common 手写类型 */
data class ScanListInput(val findOptions: CommonFindOptions? = null)

/** q_ai_deepResearch_getStatus（旧 q_ai_getDeepResearchStatus(deepResearchId)） */
data class GetDeepResearchStatusInput(val deepResearchId: UUID)

/** q_ai_collectionItem_list（旧 q_ai_findCollectionItemsByCursor(input)） */
data class ListCollectionItemsInput(
    val cursor: String? = null,
    val limit: Int? = null,
)

/** m_ai_collectionItem_add（旧 m_ai_addCollectionItem(input)） */
data class AddCollectionItemInput(val scanRecordId: UUID)

/** m_ai_collectionItem_removeMany（旧 m_ai_removeCollectionItems(input)） */
data class RemoveCollectionItemsInput(val scanRecordIds: List<UUID>)
