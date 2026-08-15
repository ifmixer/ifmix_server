package com.ifmix.api.core.graphql.common.type

/** 分页连接类型：todo 列表带游标。 */
data class TodoConnection(
    val items: List<TodoType>,
    val nextCursor: String?,
    val hasMore: Boolean,
)
