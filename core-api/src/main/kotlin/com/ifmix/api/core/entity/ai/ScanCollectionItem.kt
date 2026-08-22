package com.ifmix.api.core.entity.ai

import com.ifmix.api.core.entity.BaseAppEntity
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "ai_scan_collection_item")
interface ScanCollectionItem : BaseAppEntity {


    val collectionId: UUID
    val scanRecordId: UUID
}
