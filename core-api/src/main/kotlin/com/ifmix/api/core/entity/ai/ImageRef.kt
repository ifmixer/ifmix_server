package com.ifmix.api.core.entity.ai

/** 图片引用（存储在 JSONB 中）。position 为展示/分析顺序。后续可加 width/height/mimeType 等。 */
data class ImageRef(val key: String, val position: Int = 0)
