package com.ifmix.core.api.entity.ai

/**
 * 图片引用（存储在 `ai_scan_record.image_keys` JSONB 中）。图片顺序由数组顺序决定。
 *
 * category — 图片分类编码（Int 全链路透传，主图/默认为 0，其余从 10 起步长 10 便于插值）。
 * 完整码表见 docs/DATABASE.md「枚举码表登记 / ImageRef.category」：
 *
 * | Code | 名称        | 说明                          |
 * |------|-------------|-------------------------------|
 * | 0    | MAIN        | 主图 / 默认（初次扫描、未指定分类时的默认值）|
 * | 10   | FRONT       | 正面                          |
 * | 20   | BACK        | 背面                          |
 * | 30   | BOTTOM      | 底部 / 底面                   |
 * | 40   | MAKER_MARK  | 款识 / 签名                   |
 * | 50   | DAMAGE      | 损伤 / 磨损                   |
 * | 60   | DIMENSIONS  | 尺寸 / 比例（带参照物）        |
 * | 70   | PRICE_TAG   | 价签                          |
 * | 80   | DOCUMENTS   | 文件 / 来源证明               |
 * | 90   | DETAIL      | 局部细节（通用，可选）         |
 * | 1000 | OTHER       | 其它                          |
 */
data class ImageRef(val key: String, val category: ImageCategory? = null)

/** 图片分类编码。typealias（Int 全链路透传），码表见 [ImageCategories]。 */
typealias ImageCategory = Int

/** 图片分类码表（主图/默认 0，其余从 10 起步长 10）。见 docs/DATABASE.md。 */
object ImageCategories {
    /** 主图 / 默认。scan/DeepResearch 图片缺省 category 时统一落此值。 */
    const val MAIN: ImageCategory = 0
    const val FRONT: ImageCategory = 10
    const val BACK: ImageCategory = 20
    const val BOTTOM: ImageCategory = 30
    const val MAKER_MARK: ImageCategory = 40
    const val DAMAGE: ImageCategory = 50
    const val DIMENSIONS: ImageCategory = 60
    const val PRICE_TAG: ImageCategory = 70
    const val DOCUMENTS: ImageCategory = 80
    const val DETAIL: ImageCategory = 90
    const val OTHER: ImageCategory = 1000
}
