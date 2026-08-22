package com.ifmix.api.core.entity.ai

import com.ifmix.api.core.entity.common.BaseAppEntity
import org.babyfish.jimmer.sql.*
import java.util.UUID

@Entity
@Table(name = "ai_scan_collection")
interface ScanCollection : BaseAppEntity {


    val installId: UUID?
    val userId: UUID?
    val isDefault: Boolean
}
