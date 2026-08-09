package com.ifmix.api.core.modules.scan.service

import com.ifmix.api.core.entity.scan.ImageRef
import com.ifmix.api.core.entity.scan.ScanRecord
import com.ifmix.api.core.entity.scan.dto.ScanRecordView
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.dto.Page
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.ratelimit.RateLimiter
import com.ifmix.api.core.infra.storage.ObjectStorage
import com.ifmix.api.core.modules.scan.dto.NewScanImageInput
import com.ifmix.api.core.modules.scan.dto.ScanInput
import com.ifmix.api.core.modules.scan.dto.ScanMediaItem
import com.ifmix.api.core.modules.scan.ScanRunner
import com.ifmix.api.core.modules.scan.dto.NewScanReq
import com.ifmix.api.core.modules.scan.dto.NewScanRes
import com.ifmix.api.core.modules.scan.dto.ScanQueryInput
import com.ifmix.api.core.modules.scan.dto.UpdateScanReq
import com.ifmix.api.core.modules.scan.repo.ScanRecordRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors

@Service
open class AntiqueService(
    private val scanRunner: ScanRunner,
    private val objectStorage: ObjectStorage,
    private val rateLimiter: RateLimiter,
    private val scanRepo: ScanRecordRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val uploadExecutor = Executors.newVirtualThreadPerTaskExecutor()

    @Transactional
    fun newScan(ctx: OperationContext, req: NewScanReq): NewScanRes {
        val subject = ctx.clientIp.toString()
        val limitResult = rateLimiter.check(ctx, subject)
        if (!limitResult.allowed) {
            throw ApiError(ErrorCode.RATE_LIMITED, "daily limit exceeded")
        }

        if (req.images.isEmpty()) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "images must not be empty")
        }

        // 解析每张图：base64 → 直接转 bytes 发 AI；imageKey → presign URL 发 AI
        data class ResolvedImage(
            val mediaItem: ScanMediaItem,
            val imageKey: String,
            val rawBytes: ByteArray?, // 非 null 表示需要异步上传
        )

        val resolved = req.images.map { img ->
            validateImageInput(img)
            if (img.base64 != null) {
                val bytes = Base64.getDecoder().decode(img.base64)
                val ext = extensionFromMediaType(img.mediaType)
                val userSegment = ctx.userId?.let { "u_$it" } ?: "u_none"
                val objectKey = "app_${ctx.appId}/i_${ctx.installId}/$userSegment/antique_scan/${UuidV7.generate()}.$ext"
                ResolvedImage(
                    mediaItem = ScanMediaItem(imageData = bytes, mediaType = img.mediaType),
                    imageKey = objectKey,
                    rawBytes = bytes,
                )
            } else {
                ResolvedImage(
                    mediaItem = ScanMediaItem(
                        imageUrl = objectStorage.presignDownload(img.imageKey!!, Duration.ofMinutes(30)),
                        mediaType = img.mediaType,
                    ),
                    imageKey = img.imageKey!!,
                    rawBytes = null,
                )
            }
        }

        val scanInput = ScanInput(
            items = resolved.map { it.mediaItem },
            lang = ctx.lang,
            country = ctx.country,
            currency = ctx.currency,
        )

        val scanResult = scanRunner.run(ctx, scanInput)

        val record = scanRepo.create(
            ctx = ctx.repoCtx,
            appId = ctx.appId!!,
            imageKeys = resolved.map { ImageRef(key = it.imageKey) },
            status = scanResult.status,
            clientIp = ctx.clientIp,
            result = scanResult,
        )

        // 异步上传 base64 图片到 R2
        resolved.filter { it.rawBytes != null }.forEach { item ->
            uploadExecutor.execute {
                try {
                    objectStorage.upload(item.imageKey, item.rawBytes!!, item.mediaItem.mediaType)
                    log.debug("Async upload success: {}", item.imageKey)
                } catch (e: Exception) {
                    log.error("Async upload failed: {} - {}", item.imageKey, e.message)
                }
            }
        }

        return NewScanRes(
            id = record.id,
            result = scanResult,
        )
    }

    private fun validateImageInput(img: NewScanImageInput) {
        if (img.imageKey == null && img.base64 == null) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "imageKey or base64 is required")
        }
        if (img.imageKey != null && img.base64 != null) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "imageKey and base64 are mutually exclusive")
        }
    }

    private fun extensionFromMediaType(mediaType: String): String = when (mediaType) {
        "image/jpg" -> "jpg"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    fun getScanById(ctx: OperationContext, id: UUID): ScanRecordView {
        val record = scanRepo.findById(ctx.repoCtx, id)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "scan record not found")
        return ScanRecordView(record)
    }

    fun findByCursor(
        ctx: OperationContext,
        input: ScanQueryInput = ScanQueryInput(),
    ): Page<ScanRecord> {
        return scanRepo.findByCursor(ctx.repoCtx, input)
    }

    fun presignedUploadUrl(ctx: OperationContext, objectKey: String, contentType: String, duration: Duration): String {
        return objectStorage.presignUpload(objectKey, contentType, duration)
    }

    fun presignedDownloadUrl(ctx: OperationContext, objectKey: String, duration: Duration): String {
        return objectStorage.presignDownload(objectKey, duration)
    }

    @Transactional
    fun deleteScan(ctx: OperationContext, id: UUID) {
        scanRepo.findById(ctx.repoCtx, id) ?: throw ApiError(ErrorCode.NOT_FOUND, "scan not found")
        scanRepo.deleteById(ctx.repoCtx, id)
    }

    @Transactional
    fun updateScan(ctx: OperationContext, req: UpdateScanReq): ScanRecordView {
        val record = scanRepo.findById(ctx.repoCtx, req.id)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "scan not found")

        scanRepo.update(ctx.repoCtx, req)
        // 重新查询返回最新数据
        return ScanRecordView(scanRepo.findById(ctx.repoCtx, req.id)!!)
    }
}
