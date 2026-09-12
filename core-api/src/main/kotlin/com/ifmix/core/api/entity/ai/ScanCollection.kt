package com.ifmix.core.api.entity.ai

import com.ifmix.core.api.entity.common.BaseProjectEntity
import com.ifmix.core.api.entity.common.CustomerIdProps
import com.ifmix.core.api.entity.common.InstallIdProps
import org.babyfish.jimmer.sql.*

@Entity
@Table(name = "ai_scan_collection")
interface ScanCollection : BaseProjectEntity, CustomerIdProps, InstallIdProps {

    val isDefault: Boolean
}
