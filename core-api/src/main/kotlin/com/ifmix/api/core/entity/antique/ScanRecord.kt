package com.ifmix.api.core.entity.antique

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.SoftDeletableProps
import com.ifmix.api.core.entity.enums.ScanStatus
import com.ifmix.api.core.infra.ratelimit.Tier
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
    val imageKeys: List<ImageRef>

    val resultJson: String?

    val status: ScanStatus

    val tier: Tier

    val clientIp: String?

    /** 用户自定义名称（通过 updateOne 设置） */
    val userDisplayName: String?

    /** 用户备注（通过 updateOne 设置） */
    val userNotes: String?
}
