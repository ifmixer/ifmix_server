package com.ifmix.core.api.entity.ai

import com.ifmix.core.api.entity.common.BaseAppEntity
import com.ifmix.core.api.entity.common.CustomerIdProps
import com.ifmix.core.api.entity.common.InstallIdProps
import com.ifmix.core.api.entity.common.SoftDeletableProps
import com.ifmix.core.api.entity.common.UserPreferenceProps
import org.babyfish.jimmer.sql.*

/** 扫描状态编码。typealias（Int 全链路透传），码表见 [ScanStatuses]。 */
typealias ScanStatus = Int

/**
 * 扫描状态码表（0 保留，从 10 起步长 10）。
 * ponytail: 目前代码中仅确证 READY=20（saveNewScan 落库时）；其余状态（如处理中/失败）
 * 尚未在代码出现，未臆造。后续新增状态时在此登记。
 */
object ScanStatuses {
    const val READY: ScanStatus = 20
}

@Entity
@Table(name = "ai_scan_record")
interface ScanRecord : BaseAppEntity, SoftDeletableProps, CustomerIdProps, InstallIdProps, UserPreferenceProps {

    @Serialized
    @Column(name = "image_keys")
    val images: List<ImageRef>

    @Serialized
    @Column(name = "basic_result")
    val basicResult: Map<String, Any?>?

    val status: ScanStatus
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
