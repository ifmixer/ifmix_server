package com.ifmix.api.core.entity.ai

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

@Entity
@Table(name = "core_scan_collection_item")
interface ScanCollectionItem : AppScopedProps, SoftDeletableProps {

    @Id
    val id: UUID

    override val appId: UUID

    @ManyToOne
    @JoinColumn(name = "collection_id")
    val collection: ScanCollection

    @ManyToOne
    @JoinColumn(name = "scan_record_id")
    val scanRecord: ScanRecord
}
