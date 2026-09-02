package com.ifmix.core.api.entity.ai

import com.ifmix.core.api.entity.common.BaseAppEntity
import com.ifmix.core.api.entity.common.CustomerOwnedProps
import com.ifmix.core.api.entity.common.SoftDeletableProps
import com.ifmix.core.api.entity.common.UserPreferenceProps
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

    val status: Int
    val clientIp: String?
    val userDisplayName: String?
    val userNotes: String?
    val collected: Boolean
    @Column(name = "is_public")
    val isPublic: Boolean
    @Column(name = "has_deep_search")
    val hasDeepSearch: Boolean
    @Column(name = "prompt_version")
    val promptVersion: String
}
