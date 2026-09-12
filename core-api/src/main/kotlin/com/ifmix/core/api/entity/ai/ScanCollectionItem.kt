package com.ifmix.core.api.entity.ai

import com.ifmix.core.api.entity.common.BaseProjectEntity
import org.babyfish.jimmer.sql.*
import java.util.UUID

@Entity
@Table(name = "ai_scan_collection_item")
interface ScanCollectionItem : BaseProjectEntity {


    val collectionId: UUID
    val scanRecordId: UUID
}
