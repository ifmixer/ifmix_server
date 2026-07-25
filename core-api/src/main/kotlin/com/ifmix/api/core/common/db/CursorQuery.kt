package com.ifmix.api.core.common.db

/** 游标分页查询参数（对外请求体）。cursor 为上一页最后一条 id。 */
data class CursorQuery(
    val cursor: String? = null,
    val order: Order? = null,
    val limit: Int? = null,
) {
    enum class Order { ASC, DESC }

    fun effectiveLimit(): Int = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    fun effectiveOrder(): Order = order ?: Order.DESC

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
    }
}
