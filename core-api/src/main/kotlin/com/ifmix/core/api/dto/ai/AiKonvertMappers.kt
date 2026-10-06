package com.ifmix.core.api.dto.ai

import com.ifmix.core.api.entity.ai.ImageRef
import com.ifmix.core.api.entity.ai.ScanDeepResearch
import com.ifmix.core.api.entity.ai.ScanRecord
import io.mcarle.konvert.api.Konvert
import io.mcarle.konvert.api.Konverter
import io.mcarle.konvert.api.Mapping

/**
 * ai 模块出参视图 Konvert mapper（konvert-rollout-server.md §3 + §0.5 语法校准）。
 *
 * 生成实现为 object [AiKonvertMappersImpl]（同包，KSP 产物）。
 * Instant → String 由内置 InstantToStringConverter 完成（即 [Instant.toString] ISO-8601 UTC，与手写语义一致）。
 *
 * ⚠️ Konvert 4.5.1 的 @Konverter 接口函数只支持单参数：`latestDeepResearch` 不进 Konverter ——
 * 三个 ScanRecord 映射函数里该 target 用 constant null 占位，由调用方（AiQueryService 的
 * latestDeepResearch 批量预取编排）`copy(latestDeepResearch = …)` 补齐。
 *
 * ⚠️ Jimmer 卸载语义：[toDetailRes] 会读 basicResult（仅 findById 详情查询加载）；
 * [toLiteRes]/[toListRes] 用 constant null 占位、**绝不触碰 source.basicResult**
 * （列表视图/批量组装路径不加载 JSONB 大字段，Konvert 全属性读取会抛 UnloadedException），
 * 两者不可混用。
 */
@Konverter
interface AiKonvertMappers {

    /** @Serialized 值对象 → wire；ScanRecord.images 的 List 元素映射经此自动组合。 */
    fun toRes(source: ImageRef): ImageRefRes

    fun toRes(source: ScanDeepResearch): ScanDeepResearchRes

    /** 详情视图（q_ai_scan_getById / m_ai_scan_updateOne 写后读）：findById 全字段加载。 */
    @Konvert(mappings = [Mapping(target = "latestDeepResearch", constant = "null")])
    fun toDetailRes(source: ScanRecord): ScanRecordRes

    /** 轻量详形（collectionItem_list 批量组装）：findByIdsListView 不加载 basicResult。 */
    @Konvert(
        mappings = [
            Mapping(target = "basicResult", constant = "null"),
            Mapping(target = "latestDeepResearch", constant = "null"),
        ]
    )
    fun toLiteRes(source: ScanRecord): ScanRecordRes

    /** 列表视图（q_ai_scan_list）：ScanRecordListView 形状，basicResult 恒 null（schema 形状保留）。 */
    @Konvert(mappings = [Mapping(target = "basicResult", constant = "null")])
    fun toListRes(source: ScanRecord): ScanRecordListRes
}
