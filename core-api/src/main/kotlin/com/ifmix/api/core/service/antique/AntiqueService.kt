package com.ifmix.api.core.service.antique

import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.infra.ratelimit.RateLimiter
import com.ifmix.api.core.infra.storage.ObjectStorage
import com.ifmix.api.core.repository.antique.ScanRecordRepository
import com.ifmix.api.core.entity.antique.ScanRecord
import org.springframework.transaction.annotation.Transactional
import com.ifmix.api.core.infra.db.UuidV7
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

    @Transactional
    fun createScan(ctx: RequestContext, request: CreateScanRequest): ScanRecord {
        // 限流检查
        val subject = ctx.appId
        val limitResult = rateLimiter.check(ctx, subject)
        if (!limitResult.allowed) {
            throw ApiError(ErrorCode.RATE_LIMITED, "daily limit exceeded")
        }

        // 生成预签名上传 URL
        val objectKey = "antique/${UuidV7.generate()}.png"
        val uploadUrl = objectStorage.presignUpload(objectKey, "image/png", Duration.ofMinutes(5))

        // 创建 ScanRecord
        val scanId = UuidV7.generate().toString()
        return scanRepo.create(
            appId = ctx.appId,
            scanId = scanId,
            imageUrl = uploadUrl,
            status = Status.PENDING.name,
            tier = "FREE",
            relatedId = request.relatedId,
            clientIp = ctx.clientIp,
        )
    }

    fun getScanResult(ctx: RequestContext, id: String): ScanDto {
        val uuid = try {
            UUID.fromString(id)
        } catch (_: Exception) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "invalid UUID format")
        }
        val record = scanRepo.findById(uuid) ?: throw ApiError(ErrorCode.NOT_FOUND, "scan record not found")
        return record.toDto()
    }

    fun findByCursor(
        ctx: RequestContext,
        input: com.ifmix.api.core.infra.db.CursorQueryInput = com.ifmix.api.core.infra.db.CursorQueryInput(),
    ): com.ifmix.api.core.infra.db.Page<ScanRecord> {
        return scanRepo.findByCursorForApp(UUID.fromString(ctx.appId), input)
    }

    fun presignedUploadUrl(objectKey: String, contentType: String, duration: Duration): String {
        return objectStorage.presignUpload(objectKey, contentType, duration)
    }

    fun presignedDownloadUrl(objectKey: String, duration: Duration): String {
        return objectStorage.presignDownload(objectKey, duration)
    }

    private fun ScanRecord.toDto() = ScanDto(
        id = id.toString(),
        scanId = scanId,
        imageUrl = imageUrl,
        status = status,
        resultJson = resultJson,
        tier = tier,
        clientIp = clientIp,
        relatedId = relatedId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

data class ScanDto(
    val id: String,
    val scanId: String?,
    val imageUrl: String?,
    val status: String?,
    val resultJson: String?,
    val tier: String?,
    val clientIp: String?,
    val relatedId: String?,
    val createdAt: java.time.Instant?,
    val updatedAt: java.time.Instant?,
)

data class CreateScanRequest(val relatedId: String?)

enum class Status { PENDING, IN_PROGRESS, COMPLETED, FAILED }
