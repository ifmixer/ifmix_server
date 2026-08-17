package com.ifmix.api.core.infra.dto



/**
 * 游标分页的分页/排序参数（客户端可绑定的请求体）。
 *
 * 只含客户端可控的 cursor/sortBy/order/limit。过滤条件由 findByCursor 的 Query 参数承载、
 * readOptions 由 findByCursor 的参数承载（服务端控制）；租户 appId、软删、limit 上限、排序、
 * 读偏好由 BaseRepository 强制注入/接管，调用方无法绕过。
 */
data class CursorQueryInput(
    val cursor: String? = null,
    val sortBy: String = "id",
    val order: SortOrder = SortOrder.DESC,
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
