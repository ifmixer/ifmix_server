package com.ifmix.api.core.entity.collection

import org.babyfish.jimmer.sql.*
import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.SoftDeletableProps
import com.ifmix.api.core.entity.antique.ScanRecord
import java.util.UUID

@Entity
@Table(name = "core_collection_item")
interface CollectionItem : AppScopedProps, SoftDeletableProps {

    @Id
    val id: UUID

    override val appId: UUID

    @ManyToOne
    @JoinColumn(name = "collection_id")
    val collection: Collection

    @ManyToOne
    @JoinColumn(name = "scan_record_id")
    val scanRecord: ScanRecord
}
