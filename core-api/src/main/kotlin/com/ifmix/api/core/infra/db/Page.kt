package com.ifmix.api.core.infra.db

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "游标分页结果")
data class Page<T>(
    val items: List<T>,
    @Schema(description = "下一页游标（不透明字符串，原样回传给 cursor 参数，不要解析）。hasMore=false 时为 null。")
    val nextCursor: String? = null,
    @Schema(description = "是否还有下一页")
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
