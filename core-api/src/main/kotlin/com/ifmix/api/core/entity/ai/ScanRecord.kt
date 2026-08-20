package com.ifmix.api.core.entity.ai

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.ai.ImageRef
import com.ifmix.api.core.entity.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "core_scan_record")
interface ScanRecord : AppScopedProps, SoftDeletableProps {
    @Id
    val id: UUID


    @Serialized
    @Column(name = "image_keys")
    val imageKeys: List<ImageRef>

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
