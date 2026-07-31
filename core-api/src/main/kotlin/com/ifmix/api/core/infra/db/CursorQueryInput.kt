package com.ifmix.api.core.infra.db

/**
 * 游标分页的分页/排序参数（客户端可绑定的请求体）。
 *
 * 只含客户端可控的 cursor/sortBy/order/limit。过滤条件由 findByCursor 的 Query 参数承载、
 * readOptions 由 findByCursor 的参数承载（服务端控制）；租户 appId、软删、limit 上限、排序、
 * 读偏好由 BaseRepository 强制注入/接管，调用方无法绕过。
 */
data class CursorQueryInput(
    val cursor: String? = null,
    val sortBy: SortField? = null,
    val order: Order? = null,
    val limit: Int? = null,
) {
    enum class SortField {
        CREATED_AT,
        UPDATED_AT,
        ID;

        fun toColumnName(): String = when (this) {
            CREATED_AT -> "createdAt"
            UPDATED_AT -> "updatedAt"
            ID -> "id"
        }
    }

    enum class Order { ASC, DESC }

    fun effectiveSortBy(): String = (sortBy ?: SortField.CREATED_AT).toColumnName()
    fun effectiveOrder(): Order = order ?: Order.DESC
    fun effectiveLimit(): Int = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
    }
}
