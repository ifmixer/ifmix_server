package com.ifmix.api.core.infra.db

import com.ifmix.api.core.infra.dto.SortOrder
import io.swagger.v3.oas.annotations.media.Schema

/**
 * 游标分页的分页/排序参数（客户端可绑定的请求体）。
 *
 * 只含客户端可控的 cursor/sortBy/order/limit。过滤条件由 findByCursor 的 Query 参数承载、
 * readOptions 由 findByCursor 的参数承载（服务端控制）；租户 appId、软删、limit 上限、排序、
 * 读偏好由 BaseRepository 强制注入/接管，调用方无法绕过。
 */
data class CursorQueryInput(
    @Schema(description = "上一页返回的 nextCursor，首次请求不传")
    val cursor: String? = null,
    @Schema(description = "排序字段，默认 id")
    val sortBy: String = "id",
    @Schema(description = "排序方向，默认 DESC")
    val order: SortOrder = SortOrder.DESC,
    @Schema(description = "每页条数，默认 20，上限 100", minimum = "1", maximum = "100")
    val limit: Int = DEFAULT_LIMIT,
) {


    fun effectiveSortBy(): String = sortBy
    fun effectiveOrder(): SortOrder = order
    fun effectiveLimit(): Int = limit.coerceIn(1, MAX_LIMIT)

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
    }
}
