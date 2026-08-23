package com.ifmix.api.core.entity.ai

import com.ifmix.api.core.entity.common.BaseAppEntity
import com.ifmix.api.core.entity.common.CustomerOwnedProps
import com.ifmix.api.core.entity.common.SoftDeletableProps
import com.ifmix.api.core.entity.common.UserPreferenceProps
import org.babyfish.jimmer.sql.*

@Entity
@Table(name = "ai_scan_record")
interface ScanRecord : BaseAppEntity, SoftDeletableProps, CustomerOwnedProps, UserPreferenceProps {

    @Serialized
    @Column(name = "image_keys")
    val images: List<ImageRef>

    @Serialized
    @Column(name = "basic_result")
    val basicResult: Map<String, Any?>?

    @Serialized
    @Column(name = "premium_result")
    val premiumResult: Map<String, Any?>?

    val status: Int
    val clientIp: String?
    val userDisplayName: String?
    val userNotes: String?
    val collected: Boolean
}
