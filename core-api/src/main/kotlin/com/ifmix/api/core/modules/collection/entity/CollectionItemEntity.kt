package com.ifmix.api.core.modules.collection.entity

import com.ifmix.api.core.common.db.BaseAppEntity
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document

/**
 * collection_item：收藏条目（join 行）。
 *
 * 归属由父 collection 派生（不带 userId/installId），按 appId + collectionId 过滤。
 * partial unique 约束保证同一 scanRecord 在同一夹中仅一条未删记录。
 */
@Document(collection = "collection_item")
@CompoundIndexes(
    CompoundIndex(name = "citem_app_coll_id_idx", def = "{'appId': 1, 'collectionId': 1, '_id': 1}"),
    CompoundIndex(
        name = "citem_coll_scan_uq",
        def = "{'collectionId': 1, 'scanRecordId': 1}",
        unique = true,
        partialFilter = "{ 'deletedAt': null }",
    ),
)
class CollectionItemEntity : BaseAppEntity() {

    /** 所属收藏夹 ID（ObjectId hex 字符串）。 */
    var collectionId: String? = null

    /** 关联的古物扫描记录 ID（scan_record._id，ObjectId）。 */
    var scanRecordId: org.bson.types.ObjectId? = null
}
