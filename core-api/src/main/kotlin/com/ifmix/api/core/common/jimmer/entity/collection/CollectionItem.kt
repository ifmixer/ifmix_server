package com.ifmix.api.core.common.jimmer.entity.collection

import org.babyfish.jimmer.sql.*
import com.ifmix.api.core.common.jimmer.entity.AppScopedProps
import com.ifmix.api.core.common.jimmer.entity.antique.ScanRecord
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "collection_item")
interface CollectionItem : AppScopedProps {

    @Id
    val id: UUID

    override val appId: UUID

    @ManyToOne
    @JoinColumn(name = "collection_id")
    val collection: Collection

    @ManyToOne
    @JoinColumn(name = "scan_record_id")
    val scanRecord: ScanRecord

    // Soft delete for collection item (similar to ScanRecord)
    @LogicalDeleted("now")
    val deletedAt: Instant?

    val createdAt: Instant
    val updatedAt: Instant
}
