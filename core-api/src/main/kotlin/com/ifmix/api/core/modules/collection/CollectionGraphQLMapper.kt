package com.ifmix.api.core.modules.collection

import com.ifmix.api.core.graphql.common.type.CollectionItemType
import com.ifmix.api.core.graphql.common.type.CollectionType
import com.ifmix.api.core.graphql.common.type.ScanRecordType
import com.ifmix.api.core.modules.antique.toScanRecordType
import com.ifmix.api.core.modules.collection.CollectionDocument
import com.ifmix.api.core.modules.collection.CollectionItemDocument

/** CollectionDocument → GraphQL CollectionType 转换。 */
fun CollectionDocument.toCollectionType(): CollectionType = CollectionType(
    id = this.id,
    isDefault = this.isDefault,
    createdAt = this.createdAt,
)

/**
 * CollectionItemDocument → GraphQL CollectionItemType 转换。
 *
 * scanRecord 由调用方预加载后传入，避免 N+1。
 */
fun CollectionItemDocument.toCollectionItemType(scanRecord: ScanRecordType?): CollectionItemType =
    CollectionItemType(
        id = this.id,
        collectionId = this.collectionId ?: "",
        scanRecordId = this.scanRecordId?.toHexString() ?: "",
        scanRecord = scanRecord,
        createdAt = this.createdAt,
    )
