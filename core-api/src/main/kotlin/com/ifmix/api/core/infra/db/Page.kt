package com.ifmix.api.core.infra.db

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "游标分页结果")
data class Page<T>(
    val items: List<T>,
    @Schema(description = "下一页游标（不透明字符串，原样回传给 cursor 参数，不要解析）。hasMore=false 时为 null。")
    val nextCursor: String?,
    @Schema(description = "是否还有下一页")
    val hasMore: Boolean,
)
