package com.ifmix.core.api.bff.api.customer.ai

import com.ifmix.core.api.dto.ai.AiKonvertMappersImpl
import com.ifmix.core.api.dto.ai.DeepResearchStatusRes
import com.ifmix.core.api.dto.ai.ListCollectionItemsInput
import com.ifmix.core.api.dto.ai.ListItemsReq
import com.ifmix.core.api.dto.ai.ScanCollectionItemRes
import com.ifmix.core.api.dto.ai.ScanCollectionRes
import com.ifmix.core.api.dto.ai.ScanDeepResearchRes
import com.ifmix.core.api.dto.ai.ScanRecordListRes
import com.ifmix.core.api.dto.ai.ScanRecordRes
import com.ifmix.core.api.dto.ai.ScanStatusRes
import com.ifmix.core.api.dto.common.CommonFindOptions
import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.entity.ai.ScanDeepResearch
import com.ifmix.core.api.entity.ai.ScanRecord
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.ai.AiFacade
import com.ifmix.core.api.modules.ai.ScanCollectionFacade
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * ai 模块 RPC 聚合层（仿 DemoQueryService）：只注入 Facade，禁 import repo/handler。
 *
 * 聚合策略（禁 N+1 循环 findById）：
 * - q_ai_scan_list：1 次 findMyScans 分页即完成（ScanRecordListView 无关联字段；
 *   repo 侧 fetchBy 已排除 basicResult JSONB 大字段，对照 ScanRecordRepository.findMyScans）；
 * - q_ai_collectionItem_list：1 次 findItemsByCursor 分页根 → 收集 scanRecordId 批量查
 *   scan 列表视图（owner-scoped，不加载 basicResult，对齐 ScanRecordsDataLoader 的
 *   findByIdsListView 用法）→ 收集 latestDeepResearchId 批量查权威 DeepResearch → 按 Map 组装；
 * - q_ai_scan_getById：单条详情（findById 全字段加载）+ 权威 DeepResearch 1 次批量查询。
 *
 * 事务边界由 controller 负责（getScanStatus / getDeepResearchStatus 的惰性超时 CAS 写
 * 仍按 AiFetcher 语义包在 GlobalTxRunner.withTx 内，其余 query 不开事务）。
 */
@Service
class AiQueryService(
    private val aiService: AiFacade,
    private val collectionService: ScanCollectionFacade,
) {

    /** 单条详情：findById（全字段）+ latestDeepResearch 权威指针加载（includeLatestDeepResearch=false 时不查，
     *  latestDeepResearch 置 null——q_ai_scan_getById 的 include 契约，2026-10-06）。miss → null（controller 裁决 NOT_FOUND）。 */
    fun findScanById(ctx: ActionContext, id: UUID, includeLatestDeepResearch: Boolean = true): ScanRecordRes? {
        val record = aiService.findById(ctx, id) ?: return null
        return AiKonvertMappersImpl.toDetailRes(record)
            .copy(latestDeepResearch = if (includeLatestDeepResearch) findLatestDeepResearch(ctx, record) else null)
    }

    /** 已加载的 ScanRecord → 详情视图（m_ai_scan_updateOne 写后读复用；latestDeepResearch 按权威指针加载）。 */
    fun toDetailRes(ctx: ActionContext, record: ScanRecord): ScanRecordRes =
        AiKonvertMappersImpl.toDetailRes(record)
            .copy(latestDeepResearch = findLatestDeepResearch(ctx, record))

    /** 分页列表：1 次分页查询即完成（列表视图无关联字段，basicResult 不加载）。 */
    fun findScans(ctx: ActionContext, findOptions: CommonFindOptions?): Page<ScanRecordListRes> {
        val page = aiService.findMyScans(ctx, findOptions)
        return Page(page.items.map(AiKonvertMappersImpl::toListRes), page.pageInfo)
    }

    /** 轮询状态（含惰性超时 CAS，controller 包事务）。 */
    fun getScanStatus(ctx: ActionContext, scanId: UUID): ScanStatusRes {
        val snapshot = aiService.getScanStatus(ctx, scanId)
        return ScanStatusRes(scanId = snapshot.scanId, status = snapshot.status, errorCode = snapshot.errorCode)
    }

    /** DR 轮询状态（含惰性超时 CAS，controller 包事务）。 */
    fun getDeepResearchStatus(ctx: ActionContext, deepResearchId: UUID): DeepResearchStatusRes {
        val dr = aiService.getDeepResearchStatus(ctx, deepResearchId)
        return DeepResearchStatusRes(deepResearchId = dr.id, status = dr.status, errorCode = dr.errorCode)
    }

    /** 默认收藏夹（facade 内含「无则建默认」语义）。 */
    fun getDefaultCollection(ctx: ActionContext): ScanCollectionRes {
        val collection = collectionService.getDefault(ctx)
        return ScanCollectionRes(id = collection.id, isDefault = collection.isDefault, createdAt = collection.createdAt.toString())
    }

    /**
     * 收藏夹条目分页：先分页根、再批量组装（禁循环 findById）。
     * scanRecord 走列表视图批量查询（不加载 basicResult）；条目指向的 scan 已被删除（悬挂 item）
     * 时静默丢弃该条——GraphQL 路径此处会以 Unfetchable 报错，RPC 侧选择不放大为整页失败。
     */
    fun findCollectionItems(ctx: ActionContext, input: ListCollectionItemsInput?): Page<ScanCollectionItemRes> {
        val req = input?.let { ListItemsReq(cursor = it.cursor, limit = it.limit, collectionId = null) }
        val page = collectionService.findItemsByCursor(ctx, req)
        if (page.items.isEmpty()) return Page(items = emptyList(), pageInfo = page.pageInfo)

        val recordIds = page.items.map { it.scanRecordId }.distinct()
        val records: List<ScanRecord> = aiService.findScanRecordsListViewByIds(ctx, recordIds)
        val recordById = records.associateBy { it.id }
        val latestById: Map<UUID, ScanDeepResearch> =
            records.mapNotNull { it.latestDeepResearchId }.distinct()
                .takeIf { it.isNotEmpty() }
                ?.let { aiService.findDeepResearchByIds(ctx, it).associateBy { dr -> dr.id } }
                ?: emptyMap()

        return Page(
            items = page.items.mapNotNull { item ->
                val record = recordById[item.scanRecordId] ?: return@mapNotNull null
                ScanCollectionItemRes(
                    id = item.id,
                    scanRecord = AiKonvertMappersImpl.toLiteRes(record)
                        .copy(
                            latestDeepResearch = record.latestDeepResearchId
                                ?.let { latestById[it] }
                                ?.let(AiKonvertMappersImpl::toRes),
                        ),
                    createdAt = item.createdAt.toString(),
                )
            },
            pageInfo = page.pageInfo,
        )
    }

    /** 按权威指针取单条 DeepResearch（owner 由父 ScanRecord 保证）。 */
    private fun findLatestDeepResearch(ctx: ActionContext, record: ScanRecord): ScanDeepResearchRes? {
        val drId = record.latestDeepResearchId ?: return null
        return aiService.findDeepResearchByIds(ctx, listOf(drId))
            .firstOrNull()
            ?.let(AiKonvertMappersImpl::toRes)
    }
}
