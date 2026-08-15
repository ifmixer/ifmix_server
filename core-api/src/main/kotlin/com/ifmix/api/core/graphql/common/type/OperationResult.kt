package com.ifmix.api.core.graphql.common.type

/** 批量操作结果类型。 */
data class OperationResult(
    val success: Boolean = true,
    val modifiedCount: Int? = null,
)
