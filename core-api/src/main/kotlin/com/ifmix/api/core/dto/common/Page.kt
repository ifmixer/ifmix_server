package com.ifmix.api.core.dto.common


data class Page<T>(
    val items: List<T>,
    val nextCursor: String? = null,
    val hasMore: Boolean = nextCursor != null,
) {
    companion object {
        /**
         * 从 limit+1 查询结果构建分页。
         * @param rawItems 查询返回的 limit+1 条数据
         * @param limit 实际页大小
         * @param cursorExtractor 从最后一条记录提取游标字符串
         */
        fun <T> of(rawItems: List<T>, limit: Int, cursorExtractor: (T) -> String?): Page<T> {
            val hasMore = rawItems.size > limit
            val items = if (hasMore) rawItems.take(limit) else rawItems
            val nextCursor = if (hasMore) items.lastOrNull()?.let(cursorExtractor) else null
            return Page(items, nextCursor, hasMore)
        }
    }
}
