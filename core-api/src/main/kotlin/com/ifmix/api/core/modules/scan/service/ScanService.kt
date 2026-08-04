package com.ifmix.api.core.modules.scan.service

import com.ifmix.api.core.entity.antique.ImageRef
import com.ifmix.api.core.entity.antique.ScanRecord
import com.ifmix.api.core.entity.antique.dto.ScanRecordView
import com.ifmix.api.core.infra.dto.CursorQueryInput
import com.ifmix.api.core.infra.dto.Page
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.ratelimit.RateLimiter
import com.ifmix.api.core.infra.storage.ObjectStorage
import com.ifmix.api.core.modules.scan.dto.ScanInput
import com.ifmix.api.core.modules.scan.dto.ScanMediaItem
import com.ifmix.api.core.modules.scan.ScanRunner
import com.ifmix.api.core.modules.scan.dto.NewScanReq
import com.ifmix.api.core.modules.scan.dto.NewScanRes
import com.ifmix.api.core.modules.scan.dto.UpdateScanReq
import com.ifmix.api.core.modules.scan.repo.ScanRecordRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

@Service
open class AntiqueService(
    private val scanRunner: ScanRunner,
    private val objectStorage: ObjectStorage,
    private val rateLimiter: RateLimiter,
    private val scanRepo: ScanRecordRepository,
) {

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

        val scanInput = ScanInput(
            items = req.images.map { img ->
                ScanMediaItem(
                    imageUrl = objectStorage.presignDownload(img.imageKey, Duration.ofMinutes(30)),
                    mediaType = img.mediaType,
                )
            },
        )

        val scanResult = scanRunner.run(ctx, scanInput)

        val record = scanRepo.create(
            ctx = ctx.repoCtx,
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
        val record = scanRepo.findById(ctx.repoCtx, id)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "scan record not found")
        return ScanRecordView(record)
    }

    fun findByCursor(
        ctx: OperationContext,
        input: CursorQueryInput = CursorQueryInput(),
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
        return ScanRecordView(record)
    }
}
