package com.ifmix.api.core.modules.scan.service

import com.ifmix.api.core.entity.scan.ImageRef
import com.ifmix.api.core.entity.scan.ScanRecord
import com.ifmix.api.core.entity.scan.dto.ScanRecordDto
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
import com.ifmix.api.core.modules.scan.dto.ScanQueryInput
import com.ifmix.api.core.modules.scan.dto.UpdateScanReq
import com.ifmix.api.core.modules.scan.repo.ScanRecordRepository
import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(javaClass)

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

        val resolved = req.images.map { img ->
            ScanMediaItem(
                imageUrl = objectStorage.presignDownload(img.imageKey, Duration.ofMinutes(30)),
                mediaType = img.mediaType,
            )
        }

        val scanInput = ScanInput(
            items = resolved,
            lang = ctx.lang,
            country = ctx.country,
            currency = ctx.currency,
        )

        val scanResult = scanRunner.run(ctx, scanInput)

        val recordId = scanRepo.create(
            ctx = ctx.repoCtx,
            appId = ctx.appId!!,
            imageKeys = req.images.map { ImageRef(key = it.imageKey) },
            status = scanResult.status.code,
            clientIp = ctx.clientIp,
            result = scanResult,
        )

        return NewScanRes(
            id = recordId,
            result = scanResult,
        )
    }

    fun getScanById(ctx: OperationContext, id: UUID): ScanRecordDto {
        val appId = ctx.appId!!
        val record = scanRepo.findById(ctx.repoCtx, appId, id)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "scan record not found")
        return ScanRecordDto(record)
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
        val appId = ctx.appId!!
        val success = scanRepo.deleteById(ctx.repoCtx, appId, id)
        if(!success){
            throw ApiError(ErrorCode.NOT_FOUND, "scan not found")
        }
    }

    @Transactional
    fun updateScan(ctx: OperationContext, req: UpdateScanReq) {
        val appId = ctx.appId!!
        scanRepo.findById(ctx.repoCtx, appId, req.id)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "scan not found")
        scanRepo.update(ctx.repoCtx, req)
    }
}
