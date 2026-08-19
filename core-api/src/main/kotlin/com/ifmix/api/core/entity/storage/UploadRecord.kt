package com.ifmix.api.core.entity.storage

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.CreatedAtProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * 上传记录 — 每次 presignUpload 写入一条，用于后续校验/清理/统计。
 */
@Entity
@Table(name = "core_upload_record")
interface UploadRecord : AppScopedProps, CreatedAtProps {

    @Id
    val id: UUID


    val installId: UUID?

    @Column(name = "user_id")
    val userId: UUID?

    val objectKey: String

    val contentType: String

    val category: String

    val clientIp: String?
}
