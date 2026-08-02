package com.ifmix.api.core.service.antique

import com.ifmix.api.core.entity.antique.ImageRef
import com.ifmix.api.core.entity.enums.ScanStatus
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
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
            id = record.id.toString(),
            result = scanResult,
        )
    }

    fun getScanResult(ctx: OperationContext, id: String): ScanDto {
        val uuid = try {
            UUID.fromString(id)
        } catch (_: Exception) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "invalid UUID format")
        }
        val record = scanRepo.findById(ctx.repo, uuid) ?: throw ApiError(ErrorCode.NOT_FOUND, "scan record not found")
        return record.toDto()
    }

    fun findByCursor(
        ctx: OperationContext,
        input: com.ifmix.api.core.infra.db.CursorQueryInput = com.ifmix.api.core.infra.db.CursorQueryInput(),
    ): com.ifmix.api.core.infra.db.Page<ScanRecord> {
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
    fun deleteScan(ctx: OperationContext, id: String) {
        val uuid = try { UUID.fromString(id) } catch (_: Exception) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "invalid UUID")
        }
        scanRepo.findById(ctx.repo, uuid) ?: throw ApiError(ErrorCode.NOT_FOUND, "scan not found")
        // TODO: 校验归属（当前用户）
        scanRepo.deleteById(ctx.repo, uuid)
    }

    /**
     * 更新扫描记录（name / notes）。
     */
    @Transactional
    fun updateScan(ctx: OperationContext, req: UpdateScanReq): ScanDto {
        val uuid = try { UUID.fromString(req.id) } catch (_: Exception) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "invalid UUID")
        }
        val record = scanRepo.findById(ctx.repo, uuid) ?: throw ApiError(ErrorCode.NOT_FOUND, "scan not found")
        // TODO: 校验归属 + 实现更新
        return record.toDto()
    }

    /**
     * 从 ScanRecord 转换为 ScanDto。
     * 从 ScanRecord 构建 DTO。result 字段由 Jimmer @Serialized 自动反序列化。
     */
    fun ScanRecord.toDto(): ScanDto {
        return ScanDto(
            id = id.toString(),
            imageKeys = imageKeys.map { it.key },
            name = userDisplayName ?: result?.name,
            isAntique = result?.isAntique,
            currency = result?.priceCurrency,
            userNotes = userNotes,
            collected = false, // TODO: 查收藏状态
            status = status,
            result = result,
            createdAt = createdAt.toEpochMilli(),
            updatedAt = updatedAt.toEpochMilli(),
        )
    }
}
@io.swagger.v3.oas.annotations.media.Schema(description = "扫描记录 DTO。列表页和详情页统一使用。")
data class ScanDto(
    @io.swagger.v3.oas.annotations.media.Schema(description = "记录主键（UUIDv7）。所有需要传 scanRecordId 的地方都用这个。")
    val id: String,
    @io.swagger.v3.oas.annotations.media.Schema(description = "图片 objectKey 列表（永久标识）。用于调 presignDownload 续签 URL。")
    val imageKeys: List<String>,
    @io.swagger.v3.oas.annotations.media.Schema(description = "古物名称。初始为 AI result.name 的快照；用户通过 updateOne 改名后变为用户设定值。列表页优先用此字段，为 null 时 fallback 到 result.name。")
    val name: String?,
    @io.swagger.v3.oas.annotations.media.Schema(description = "是否古物快照（从 result.isAntique 提取）。列表页优先用此字段。")
    val isAntique: Boolean?,
    @io.swagger.v3.oas.annotations.media.Schema(description = "扫描时用户设置的货币（来自 x-currency header）。")
    val currency: String?,
    @io.swagger.v3.oas.annotations.media.Schema(description = "用户自定义备注（通过 updateOne 设置）。")
    val userNotes: String?,
    @io.swagger.v3.oas.annotations.media.Schema(description = "是否已被当前用户收藏。")
    val collected: Boolean,
    @io.swagger.v3.oas.annotations.media.Schema(description = "扫描状态。customer 接口中通常为 COMPLETED。")
    val status: ScanStatus,
    @io.swagger.v3.oas.annotations.media.Schema(description = "完整 AI 识别结果。status=COMPLETED 时非 null；status=PENDING 时为 null。")
    val result: ScanResult?,
    @io.swagger.v3.oas.annotations.media.Schema(description = "创建时间（epoch millis）")
    val createdAt: Long,
    @io.swagger.v3.oas.annotations.media.Schema(description = "最后更新时间（epoch millis）")
    val updatedAt: Long?,
)


/** API 输入：扫描图片项 */
data class NewScanImageInput(
    /** presignUpload 返回的 imageKey */
    val imageKey: String,
    /** MIME 类型（image/jpeg, image/png, image/webp） */
    val mediaType: String,
)

data class NewScanReq(val images: List<NewScanImageInput>)

data class NewScanRes(
    val id: String,
    val result: ScanResult,
)

data class DeleteScanRes(
    @io.swagger.v3.oas.annotations.media.Schema(description = "是否删除成功")
    val deleted: Boolean,
)

data class UpdateScanReq(
    @io.swagger.v3.oas.annotations.media.Schema(description = "记录 ID（UUIDv7）")
    val id: String,
    @io.swagger.v3.oas.annotations.media.Schema(description = "新名称。不传=不修改；传 null=清空（回退到 result.name 快照）；传字符串=设为用户自定义名称。")
    val name: String? = null,
    @io.swagger.v3.oas.annotations.media.Schema(description = "用户备注。不传=不修改；传 null=清空；传字符串=设为用户备注。")
    val userNotes: String? = null,
)
