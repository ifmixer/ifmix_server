package com.ifmix.core.api.entity.ai

import com.ifmix.core.api.entity.common.BaseAppEntity
import org.babyfish.jimmer.sql.*
import java.util.UUID

@Entity
@Table(name = "ai_scan_collection_item")
interface ScanCollectionItem : BaseAppEntity {


    val collectionId: UUID
    val scanRecordId: UUID
}
