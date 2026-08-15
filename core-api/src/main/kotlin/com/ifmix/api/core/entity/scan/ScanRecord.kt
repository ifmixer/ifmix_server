package com.ifmix.api.core.entity.scan

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * 古物扫描记录。
 */
@Entity
@Table(name = "core_scan_record")
interface ScanRecord : AppScopedProps, SoftDeletableProps {
    @Id
    val id: UUID

    override val appId: UUID

    /** 图片列表（JSONB 对象数组） */
    @Serialized
    @Column(name = "image_keys")
    val images: List<ImageRef>

    /** AI 识别结果（JSONB） */
    @Serialized
    @Column(name = "result_json")
    val result: Map<String, Any?>?

    /** 扫描状态编码。0=UNKNOWN, 100=PENDING, 110=PROCESSING, 200=COMPLETED, 300=FAILED */
    val status: Int

    val clientIp: String?

    /** 语言代码，如 zh-CN, en-US */
    val lang: String?

    /** 国家/地区代码，如 CN, US */
    val country: String?

    /** 货币代码，如 CNY, USD */
    val currency: String?

    /** 用户自定义名称（通过 updateOne 设置） */
    val userDisplayName: String?

    /** 用户备注（通过 updateOne 设置） */
    val userNotes: String?

    /** 是否已收藏 */
    val collected: Boolean
}
