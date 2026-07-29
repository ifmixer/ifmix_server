package com.ifmix.api.core.common.jimmer.entity.collection

/**
 * Collection 的 DTO 视图。
 */
interface CollectionDto : Collection {
    // Include items projection or additional fields if needed
    val itemsCount: Long?
}
