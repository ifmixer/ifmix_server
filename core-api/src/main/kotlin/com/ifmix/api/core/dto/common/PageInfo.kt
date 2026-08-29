package com.ifmix.api.core.dto.common

data class PageInfo(
    val nextCursor: String? = null,
    val hasMore: Boolean = nextCursor != null,
)
