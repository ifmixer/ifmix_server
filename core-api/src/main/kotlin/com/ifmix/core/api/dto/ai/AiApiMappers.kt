package com.ifmix.core.api.dto.ai

import com.ifmix.core.api.entity.ai.ScanDeepResearch
import com.ifmix.core.api.entity.ai.ScanRecord

/**
 * ai 模块出参视图 mapper（纯 object，无 Spring 依赖）。
 *
 * 入参侧无需 mapper：协议 DTO（[AiInputs.kt]）由 controller 经 Jackson 直接反序列化。
 * DateTime 统一 [Instant.toString] ISO-8601 UTC。
 *
 * 注意 Jimmer 卸载语义：[toDetailRes] 会读 basicResult（仅详情查询加载）；
 * [toLiteRes] 不触碰 basicResult（列表视图 / 批量组装路径不加载 JSONB 大字段），
 * 两者不可混用，否则 UnloadedException。
 */
object AiApiMappers {

    fun imageRefToDto(images: List<com.ifmix.core.api.entity.ai.ImageRef>): List<ImageRefRes> =
        images.map { ImageRefRes(key = it.key, category = it.category) }

    fun deepResearchToDto(dr: ScanDeepResearch): ScanDeepResearchRes =
        ScanDeepResearchRes(
            id = dr.id,
            scanRecordId = dr.scanRecordId,
            premiumResult = dr.premiumResult,
            createdAt = dr.createdAt.toString(),
            updatedAt = dr.updatedAt?.toString(),
        )

    /**
     * 详情视图（q_ai_scan_getById）：basicResult + latestDeepResearch 完整加载。
     * [latestDeepResearch] 由调用方按 scan_record.latest_deep_research_id 权威指针批量取好传入。
     */
    fun toDetailRes(record: ScanRecord, latestDeepResearch: ScanDeepResearchRes?): ScanRecordRes =
        ScanRecordRes(
            id = record.id,
            createdAt = record.createdAt.toString(),
            updatedAt = record.updatedAt?.toString(),
            isPublic = record.isPublic,
            status = record.status,
            errorCode = record.errorCode,
            locale = record.locale,
            country = record.country,
            currency = record.currency,
            userDisplayName = record.userDisplayName,
            userNotes = record.userNotes,
            collected = record.collected,
            hasDeepSearch = record.hasDeepSearch,
            images = imageRefToDto(record.images),
            basicResult = record.basicResult,
            latestDeepResearch = latestDeepResearch,
        )

    /**
     * 轻量详形（collectionItem_list 组装）：**不读 basicResult**（批量查询用 findByIdsListView
     * 不加载 JSONB 大字段，对齐 ScanRecordsDataLoader）；latestDeepResearch 同样批量预取。
     */
    fun toLiteRes(record: ScanRecord, latestDeepResearch: ScanDeepResearchRes?): ScanRecordRes =
        ScanRecordRes(
            id = record.id,
            createdAt = record.createdAt.toString(),
            updatedAt = record.updatedAt?.toString(),
            isPublic = record.isPublic,
            status = record.status,
            errorCode = record.errorCode,
            locale = record.locale,
            country = record.country,
            currency = record.currency,
            userDisplayName = record.userDisplayName,
            userNotes = record.userNotes,
            collected = record.collected,
            hasDeepSearch = record.hasDeepSearch,
            images = imageRefToDto(record.images),
            basicResult = null,
            latestDeepResearch = latestDeepResearch,
        )

    /** 列表视图（q_ai_scan_list）：ScanRecordListView 形状，basicResult 恒 null（schema 形状保留）。 */
    fun toListRes(record: ScanRecord): ScanRecordListRes =
        ScanRecordListRes(
            id = record.id,
            images = imageRefToDto(record.images),
            status = record.status,
            errorCode = record.errorCode,
            basicResult = null,
            locale = record.locale,
            country = record.country,
            currency = record.currency,
            userDisplayName = record.userDisplayName,
            userNotes = record.userNotes,
            collected = record.collected,
            isPublic = record.isPublic,
            createdAt = record.createdAt.toString(),
            updatedAt = record.updatedAt?.toString(),
        )
}
