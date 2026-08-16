package com.ifmix.api.core.modules.collection

import com.ifmix.api.core.graphql.generated.types.CollectionItemType
import com.ifmix.api.core.graphql.generated.types.Collection
import com.ifmix.api.core.graphql.generated.types.ScanRecord
import com.ifmix.api.core.modules.antique.toScanRecord
import com.ifmix.api.core.modules.collection.CollectionDocument
import com.ifmix.api.core.modules.collection.CollectionItemDocument

/** CollectionDocument → GraphQL Collection 转换。 */
fun CollectionDocument.toCollection(): Collection = Collection(
    id = this.id.toHexString(),
    isDefault = this.isDefault,
    createdAt = this.createdAt,
)

/**
 * CollectionItemDocument → GraphQL CollectionItemType 转换。
 *
 * scanRecord 由调用方预加载后传入，避免 N+1。
 */
fun CollectionItemDocument.toCollectionItemType(scanRecord: ScanRecord?): CollectionItemType =
    CollectionItemType(
        id = this.id.toHexString(),
        collectionId = this.collectionId ?: "",
        scanRecordId = this.scanRecordId?.toHexString() ?: "",
        scanRecord = scanRecord,
        createdAt = this.createdAt,
    )
