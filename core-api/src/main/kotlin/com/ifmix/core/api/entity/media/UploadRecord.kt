package com.ifmix.core.api.entity.media

import com.ifmix.core.api.entity.common.AppScopedProps
import com.ifmix.core.api.entity.common.ActorType
import com.ifmix.core.api.entity.common.UUIDProps
import com.ifmix.core.api.entity.common.CreatedAtProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * 上传记录 — 每次 presignUpload 写入一条，用于后续校验/清理/统计。
 * 主体无关：以 actorId + actorType 关联（customer / 未来 manager）。
 */
@Entity
@Table(name = "media_upload_record")
interface UploadRecord : UUIDProps, AppScopedProps, CreatedAtProps {

    /** 逻辑外键 → 主体 id（customer / manager，跨模块） */
    val actorId: UUID

    /** 主体类型：10=customer / 20=manager */
    val actorType: ActorType

    val objectKey: String
    val contentType: String
    val clientIp: String?
}
