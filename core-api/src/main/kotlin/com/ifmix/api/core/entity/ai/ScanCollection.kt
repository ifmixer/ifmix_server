package com.ifmix.api.core.entity.ai

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "core_scan_collection")
interface ScanCollection : AppScopedProps, SoftDeletableProps {

    @Id
    val id: UUID

    val appId: UUID

    val installId: UUID?
    val userId: UUID?
    val isDefault: Boolean

    @OneToMany(mappedBy = "collection")
    val items: List<ScanCollectionItem>
}
