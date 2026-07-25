package com.ifmix.api.core.common.db

/** 游标分页结果。nextCursor 为最后一条的 id（hex），无更多则 null。 */
data class Page<T>(val items: List<T>, val nextCursor: String?, val hasMore: Boolean)
