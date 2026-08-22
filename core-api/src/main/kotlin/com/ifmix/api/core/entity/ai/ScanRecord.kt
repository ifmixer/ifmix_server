package com.ifmix.api.core.entity.ai

import com.ifmix.api.core.entity.common.BaseAppEntity
import com.ifmix.api.core.entity.common.SoftDeletableProps
import org.babyfish.jimmer.sql.*

@Entity
@Table(name = "ai_scan_record")
interface ScanRecord : BaseAppEntity, SoftDeletableProps {


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
    val lang: String?
    val country: String?
    val currency: String?
    val userDisplayName: String?
    val userNotes: String?
    val collected: Boolean
}
