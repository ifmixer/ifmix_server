package com.ifmix.api.core.graphql.common.type

import java.time.Instant

/** GraphQL 层 Collection 展示类型。 */
data class CollectionType(
    val id: String,
    val isDefault: Boolean,
    val createdAt: Instant,
)

/** GraphQL 层 CollectionItem 展示类型，含关联的扫描记录。 */
data class CollectionItemType(
    val id: String,
    val collectionId: String,
    val scanRecordId: String,
    val scanRecord: ScanRecordType?,
    val createdAt: Instant,
)

/** 带游标的收藏条目分页连接。 */
data class CollectionItemConnection(
    val items: List<CollectionItemType>,
    val nextCursor: String?,
    val hasMore: Boolean,
)
