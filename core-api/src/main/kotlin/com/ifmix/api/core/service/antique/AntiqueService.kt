package com.ifmix.api.core.service.antique

import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.infra.ratelimit.RateLimiter
import com.ifmix.api.core.infra.ratelimit.Tier
import com.ifmix.api.core.infra.storage.ObjectStorage
import com.ifmix.api.core.repository.antique.ScanRecordRepository
import com.ifmix.api.core.entity.antique.ScanRecord
import kotlinx.coroutines.runBlocking
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

    private val objectMapper: tools.jackson.databind.ObjectMapper by lazy {
        tools.jackson.databind.json.JsonMapper.builder()
            .addModule(tools.jackson.module.kotlin.KotlinModule.Builder().build())
            .propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
            .build()
    }

    /**
     * P0-1: AI 识别端点 — 接受已上传图片的 objectKey，调用 ScanRunner 获取结果。
     */
    @Transactional
    fun newScan(ctx: RequestContext, req: NewScanReq): NewScanRes {
        // 限流检查
        val subject = ctx.appId
        val limitResult = rateLimiter.check(ctx, subject)
        if (!limitResult.allowed) {
            throw ApiError(ErrorCode.RATE_LIMITED, "daily limit exceeded")
        }

        // 生成预签名下载 URL 供 AI 模型访问图片
        val imageUrl = objectStorage.presignDownload(req.imageKey, Duration.ofMinutes(30))

        // 调用 ScanRunner（suspend fun，用 runBlocking 包装）
        val scanResult = runBlocking {
            scanRunner.run(ctx, imageUrl)
        }

        // 序列化结果为 JSON
        val resultJson = try {
            objectMapper.writeValueAsString(scanResult)
        } catch (_: Exception) {
            null
        }

        // 创建 ScanRecord
        val record = scanRepo.create(
            appId = ctx.appId,
            scanId = scanResult.scanId,
            imageUrl = req.imageKey,
            status = scanResult.status.name,
            tier = "FREE",
            relatedId = null,
            clientIp = ctx.clientIp,
            resultJson = resultJson,
        )

        return NewScanRes(
            id = record.id.toString(),
            result = scanResult,
        )
    }

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

    /**
     * 从 ScanRecord 转换为 ScanDto。
     * 尝试从 resultJson 解析 ScanResult 对象，并提取字段。
     */
    fun ScanRecord.toDto(): ScanDto {
        val parsedResult = parseResultJson(this.resultJson)
        return ScanDto(
            id = id.toString(),
            scanId = scanId,
            imageUrl = imageUrl ?: "",
            imageKey = imageUrl, // imageUrl 实际存的是 imageKey
            name = parsedResult?.name,
            isAntique = parsedResult?.isAntique,
            currency = parsedResult?.priceCurrency,
            collected = false, // 暂无 collected 字段，默认 false
            status = status?.let { try { ScanResult.Status.valueOf(it) } catch (_: Exception) { null } },
            result = parsedResult,
            language = null, // ScanRecord 无 language 字段
            tier = tier?.let { try { Tier.valueOf(it) } catch (_: Exception) { null } },
            createdAt = createdAt.toEpochMilli(),
            updatedAt = updatedAt.toEpochMilli(),
        )
    }

    /**
     * 解析 resultJson 为 ScanResult 对象。
     */
    private fun parseResultJson(json: String?): ScanResult? {
        if (json.isNullOrBlank()) return null
        return try {
            objectMapper.readValue(json, ScanResult::class.java)
        } catch (_: Exception) {
            null
        }
    }
}

data class ScanDto(
    val id: String,
    val scanId: String?,
    val imageUrl: String,
    val imageKey: String?,
    val name: String?,
    val isAntique: Boolean?,
    val currency: String?,
    val collected: Boolean,
    val status: ScanResult.Status?,
    val result: ScanResult?,
    val language: String?,
    val tier: Tier?,
    val createdAt: Long,
    val updatedAt: Long?,
)

data class CreateScanRequest(val relatedId: String?)

data class NewScanReq(val imageKey: String)

data class NewScanRes(
    val id: String,
    val result: ScanResult,
)

enum class Status { PENDING, IN_PROGRESS, COMPLETED, FAILED }
