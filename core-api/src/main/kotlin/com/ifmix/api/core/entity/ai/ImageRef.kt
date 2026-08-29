package com.ifmix.api.core.entity.ai

/** 图片引用（存储在 JSONB 中）。view 为物品视角编码（如 0=front, 1=side, 2=back）。顺序由数组顺序决定。 */
data class ImageRef(val key: String, val view: Int? = null)
