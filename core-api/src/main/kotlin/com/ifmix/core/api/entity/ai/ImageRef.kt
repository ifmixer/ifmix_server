package com.ifmix.core.api.entity.ai

/**
 * 图片引用（存储在 `ai_scan_record.image_keys` JSONB 中）。图片顺序由数组顺序决定。
 *
 * category — 图片分类编码（Int 全链路透传，0 留白，主视图 10，间隔 10 便于插值）。
 * 完整码表见 docs/DATABASE.md「枚举码表登记 / ImageRef.category」：
 *
 * | Code | 名称        | 说明                          |
 * |------|-------------|-------------------------------|
 * | 0    | UNSPECIFIED | 未指定/默认（如初次扫描的主图）|
 * | 10   | FRONT       | 正面                          |
 * | 20   | BACK        | 背面                          |
 * | 30   | BOTTOM      | 底部 / 底面                   |
 * | 40   | MAKER_MARK  | 款识 / 签名                   |
 * | 50   | DAMAGE      | 损伤 / 磨损                   |
 * | 60   | DIMENSIONS  | 尺寸 / 比例（带参照物）        |
 * | 70   | PRICE_TAG   | 价签                          |
 * | 80   | DOCUMENTS   | 文件 / 来源证明               |
 * | 90   | DETAIL      | 局部细节（通用，可选）         |
 * | 100  | OTHER       | 其它                          |
 */
data class ImageRef(val key: String, val category: Int? = null)
