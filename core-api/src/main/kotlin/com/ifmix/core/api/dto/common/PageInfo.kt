package com.ifmix.core.api.dto.common

data class PageInfo(
    val nextCursor: String? = null,
    val hasMore: Boolean = nextCursor != null,
)
