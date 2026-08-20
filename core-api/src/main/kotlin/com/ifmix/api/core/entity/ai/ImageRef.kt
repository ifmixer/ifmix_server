package com.ifmix.api.core.entity.ai

/** 图片引用（存储在 JSONB 中）。后续可加 width/height/mimeType 等。 */
data class ImageRef(val key: String)
