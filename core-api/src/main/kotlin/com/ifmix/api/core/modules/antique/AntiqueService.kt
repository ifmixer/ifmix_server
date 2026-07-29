package com.ifmix.api.core.modules.antique

import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.ratelimit.RateLimiter
import com.ifmix.api.core.common.storage.ObjectStorage
import com.ifmix.api.core.common.jimmer.repository.antique.ScanRecordRepository
import com.ifmix.api.core.common.jimmer.entity.antique.ScanRecord
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

/**
 * 古物扫描业务编排。
 */
class AntiqueService(
    private val scanRunner: ScanRunner,
    private val objectStorage: ObjectStorage,
    private val rateLimiter: RateLimiter,
    private val scanRepo: ScanRecordRepository,
) {

    @Transactional
    fun createScan(ctx: RequestContext, request: CreateScanRequest): String {
        // 限流检查
        val subject = ctx.appId
        val limitResult = rateLimiter.check(ctx, subject)
        if (!limitResult.allowed) {
            throw ApiError(ErrorCode.RATE_LIMITED, "daily limit exceeded")
        }

        // 生成预签名上传 URL
        val objectKey = "antique/${UUID.randomUUID()}.png"
        val uploadUrl = objectStorage.presignUpload(objectKey, "image/png", Duration.ofMinutes(5))

        // 创建 ScanRecord — 通过仓库的 create 方法
        val scanId = UUID.randomUUID().toString()
        return scanRepo.create(
            appId = ctx.appId,
            scanId = scanId,
            imageUrl = uploadUrl,
            status = Status.PENDING.toString(),
            tier = "FREE",
            relatedId = request.relatedId,
            clientIp = ctx.clientIp,
        )
    }

    fun getScanResult(ctx: RequestContext, id: String): ScanDto {
        // Find by primary key id
        val uuid = try { UUID.fromString(id) } catch (e: Exception) { throw ApiError(ErrorCode.INVALID_ID, "invalid UUID") }
        // Use base repository findById
        val record = scanRepo.findById(uuid) ?: throw ApiError(ErrorCode.NOT_FOUND, "scan record not found")
        return ScanDto(
            id = record.id.toString(),
            scanId = record.scanId,
            imageUrl = record.imageUrl,
            status = record.status,
            resultJson = record.resultJson,
            tier = record.tier,
            clientIp = record.clientIp,
            relatedId = record.relatedId,
            createdAt = record.createdAt,
            updatedAt = record.updatedAt,
        )
    }

    fun findByCursor(
        ctx: RequestContext,
        input: com.ifmix.api.core.common.db.CursorQueryInput = com.ifmix.api.core.common.db.CursorQueryInput(),
    ): com.ifmix.api.core.common.db.Page<ScanRecord> {
        val all = scanRepo.findAll()
        val appId = ctx.appId
        val filtered = all.filter { it.appId == appId && it.deletedAt == null }
        val sorted = filtered.sortedByDescending { it.createdAt }
        val limit = input.effectiveLimit()
        val hasMore = sorted.size > limit
        val items = if (hasMore) sorted.take(limit) else sorted
        return com.ifmix.api.core.common.db.Page(items, null, hasMore)
    }

    fun presignedUploadUrl(objectKey: String, contentType: String, duration: Duration): String {
        return objectStorage.presignUpload(objectKey, contentType, duration)
    }

    fun presignedDownloadUrl(objectKey: String, duration: Duration): String {
        return objectStorage.presignDownload(objectKey, duration)
    }
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
