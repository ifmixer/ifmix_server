package com.ifmix.core.api.dto.common

/**
 * 与 schema/common/filter.graphqls 一一对应（RPC 入参侧，与 generated types 的结构一致，
 * 但 op 用字符串透传、JSON 值用 Any，由 mapper 校验后转 generated 枚举/类型）。
 */
data class FilterGroup(val and: List<FilterExpr>? = null, val or: List<FilterExpr>? = null)
data class FilterExpr(val field: FieldFilter? = null, val group: FilterGroup? = null)
data class FieldFilter(
    val field: String, val op: String,
    val value: Any? = null,            // JSON 标量
    val values: List<Any>? = null,     // JSON 数组
)

/** 合法 filter op（协议字符串，大小写敏感）。 */
val FILTER_OPS = setOf("EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "NIN", "LIKE", "IS_NULL", "IS_NOT_NULL")

/** 非法 op 抛 INVALID_REQUEST（消费边界调用；纯函数便于单测）。 */
fun requireFilterOp(op: String) {
    if (op !in FILTER_OPS) {
        throw com.ifmix.core.api.infra.http.ApiError(
            com.ifmix.core.api.infra.http.ErrorCode.INVALID_REQUEST, "invalid filter op: $op")
    }
}
