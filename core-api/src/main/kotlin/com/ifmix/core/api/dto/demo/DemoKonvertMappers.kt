package com.ifmix.core.api.dto.demo

import com.ifmix.core.api.entity.demo.Todo
import com.ifmix.core.api.entity.demo.TodoItem
import com.ifmix.core.api.entity.demo.TodoRecommend
import io.mcarle.konvert.api.Konvert
import io.mcarle.konvert.api.Konverter
import io.mcarle.konvert.api.Mapping

/**
 * demo 模块出参视图 Konvert mapper（K2 试点，konvert-rollout-server.md §2）。
 *
 * 生成实现为 object [DemoKonvertMappersImpl]（同包，KSP 产物）。
 * Instant → String 由内置 InstantToStringConverter 完成（即 [Instant.toString] ISO-8601 UTC，与手写语义一致）。
 *
 * ⚠️ Konvert 4.5.1 的 @Konverter 接口函数只支持单参数：todo + items + counts 的多参数聚合
 * 不进 Konverter，按文档 §2.1 注意事项 4 的回退路径 —— 本接口只做单源映射，
 * [TodoRes.items] / itemCount / pendingCount / finishCount 生成占位值（空列表 / 0），
 * 由调用方（DemoQueryService.assemble）查询编排后 `copy` 补齐。
 */
@Konverter
interface DemoKonvertMappers {

    /** ⚠️ Jimmer entity 接口源可行性验证点（文档 §0 决策树）：若编译不过或语义错误则整体回退手写。 */
    fun toRes(source: TodoItem): TodoItemRes

    /** @Serialized JSONB 值对象 → wire；内嵌 RecItem 经下方函数自动组合 List 元素映射。 */
    fun toRes(source: TodoRecommend): TodoRecommendRes

    fun toRes(source: TodoRecommend.RecItem): TodoRecItemRes

    @Konvert(
        mappings = [
            Mapping(target = "items", constant = "emptyList()"),
            Mapping(target = "itemCount", constant = "0"),
            Mapping(target = "pendingCount", constant = "0"),
            Mapping(target = "finishCount", constant = "0"),
        ]
    )
    fun toRes(source: Todo): TodoRes
}
