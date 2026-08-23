package com.ifmix.api.core.entity.ai

import com.ifmix.api.core.entity.common.BaseAppEntity
import com.ifmix.api.core.entity.common.CustomerOwnedProps
import org.babyfish.jimmer.sql.*

@Entity
@Table(name = "ai_scan_collection")
interface ScanCollection : BaseAppEntity, CustomerOwnedProps {

    val isDefault: Boolean
}
