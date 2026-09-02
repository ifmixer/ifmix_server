package com.ifmix.core.api.entity.media

import com.ifmix.core.api.entity.common.AppScopedProps
import com.ifmix.core.api.entity.common.UUIDProps
import com.ifmix.core.api.entity.common.CreatedAtProps
import com.ifmix.core.api.entity.common.CustomerOwnedProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * 上传记录 — 每次 presignUpload 写入一条，用于后续校验/清理/统计。
 */
@Entity
@Table(name = "media_upload_record")
interface UploadRecord : UUIDProps, AppScopedProps, CreatedAtProps, CustomerOwnedProps {

    val objectKey: String
    val contentType: String
    val category: String
    val clientIp: String?
}
