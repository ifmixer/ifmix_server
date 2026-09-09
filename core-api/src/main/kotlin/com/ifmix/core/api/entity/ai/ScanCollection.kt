package com.ifmix.core.api.entity.ai

import com.ifmix.core.api.entity.common.BaseAppEntity
import com.ifmix.core.api.entity.common.CustomerIdProps
import com.ifmix.core.api.entity.common.InstallIdProps
import org.babyfish.jimmer.sql.*

@Entity
@Table(name = "ai_scan_collection")
interface ScanCollection : BaseAppEntity, CustomerIdProps, InstallIdProps {

    val isDefault: Boolean
}
