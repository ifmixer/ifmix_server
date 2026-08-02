package com.ifmix.api.core.service.antique

import com.ifmix.api.core.entity.antique.ImageRef
import com.ifmix.api.core.entity.antique.ScanRecord
import com.ifmix.api.core.entity.antique.dto.ScanRecordView
import com.ifmix.api.core.entity.enums.ScanStatus
import com.ifmix.api.core.infra.db.CursorQueryInput
import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.ratelimit.RateLimiter
import com.ifmix.api.core.infra.storage.ObjectStorage
import com.ifmix.api.core.repository.antique.ScanRecordRepository
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

/**
 * 古物扫描业务编排。
 */
@org.springframework.stereotype.Service
open class AntiqueService(
    private val scanRunner: ScanRunner,
    private val objectStorage: ObjectStorage,
    private val rateLimiter: RateLimiter,
    private val scanRepo: ScanRecordRepository,
) {

    /**
     * P0-1: AI 识别端点 — 接受已上传图片的 objectKey，调用 ScanRunner 获取结果。
     */
    @Transactional
    fun newScan(ctx: OperationContext, req: NewScanReq): NewScanRes {
        // 限流检查
        val subject = ctx.appId.toString()
        val limitResult = rateLimiter.check(ctx, subject)
        if (!limitResult.allowed) {
            throw ApiError(ErrorCode.RATE_LIMITED, "daily limit exceeded")
        }

        if (req.images.isEmpty()) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "images must not be empty")
        }

        // 构建 ScanInput：为每张图片生成预签名下载 URL
        val scanInput = ScanInput(
            items = req.images.map { img ->
                ScanMediaItem(
                    imageUrl = objectStorage.presignDownload(img.imageKey, Duration.ofMinutes(30)),
                    mediaType = img.mediaType,
                )
            },
        )

        // 调用 AI 扫描（同步）
        val scanResult = scanRunner.run(ctx, scanInput)

        // 创建 ScanRecord（result 字段由 Jimmer @Serialized 自动序列化为 JSONB）
        val record = scanRepo.create(
            repo = ctx.repo,
            appId = ctx.appId!!,
            imageKeys = req.images.map { ImageRef(key = it.imageKey) },
            status = scanResult.status,
            clientIp = ctx.clientIp,
            result = scanResult,
        )

        return NewScanRes(
            id = record.id,
            result = scanResult,
        )
    }

    fun getScanById(ctx: OperationContext, id: UUID): ScanRecordView {
        val record = scanRepo.findById(ctx.repo, id)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "scan record not found")
        return ScanRecordView(record)
    }

    fun findByCursor(
        ctx: OperationContext,
        input: CursorQueryInput = CursorQueryInput(),
    ): Page<ScanRecord> {
        return scanRepo.findByCursor(ctx.repo, input)
    }

    fun presignedUploadUrl(ctx: OperationContext, objectKey: String, contentType: String, duration: Duration): String {
        return objectStorage.presignUpload(objectKey, contentType, duration)
    }

    fun presignedDownloadUrl(ctx: OperationContext, objectKey: String, duration: Duration): String {
        return objectStorage.presignDownload(objectKey, duration)
    }

    /**
     * 软删除扫描记录。Jimmer @LogicalDeleted 自动设置 deletedAt。
     */
    @Transactional
    fun deleteScan(ctx: OperationContext, id: UUID) {
        scanRepo.findById(ctx.repo, id) ?: throw ApiError(ErrorCode.NOT_FOUND, "scan not found")
        // TODO: 校验归属（当前用户）
        scanRepo.deleteById(ctx.repo, id)
    }

    /**
     * 更新扫描记录（name / notes）。
     */
    @Transactional
    fun updateScan(ctx: OperationContext, req: UpdateScanReq): ScanRecordView {
        val record = scanRepo.findById(ctx.repo, req.id)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "scan not found")
        // TODO: 校验归属 + 实现更新
        return ScanRecordView(record)
    }
}

/** API 输入：扫描图片项 */
data class NewScanImageInput(
    /** presignUpload 返回的 imageKey */
    val imageKey: String,
    /** MIME 类型（image/jpeg, image/png, image/webp） */
    val mediaType: String,
)

data class NewScanReq(val images: List<NewScanImageInput>)

data class NewScanRes(
    val id: UUID,
    val result: ScanResult,
)

data class UpdateScanReq(
    @io.swagger.v3.oas.annotations.media.Schema(description = "记录 ID（UUIDv7）")
    val id: UUID,
    @io.swagger.v3.oas.annotations.media.Schema(description = "新名称。不传=不修改；传 null=清空（回退到 result.name 快照）；传字符串=设为用户自定义名称。")
    val name: String? = null,
    @io.swagger.v3.oas.annotations.media.Schema(description = "用户备注。不传=不修改；传 null=清空；传字符串=设为用户备注。")
    val userNotes: String? = null,
)
