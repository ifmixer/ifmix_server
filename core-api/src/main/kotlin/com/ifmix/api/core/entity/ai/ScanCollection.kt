package com.ifmix.api.core.entity.ai

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.MutableProps
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "core_scan_collection")
interface ScanCollection : AppScopedProps, MutableProps {
    @Id
    val id: UUID
    override val appId: UUID

    val installId: UUID?
    val userId: UUID?
    val isDefault: Boolean
}
