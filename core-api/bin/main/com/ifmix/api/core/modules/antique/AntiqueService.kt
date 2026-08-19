package com.ifmix.api.core.modules.antique

import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.ratelimit.RateLimiter
import com.ifmix.api.core.common.storage.ObjectStorage
import com.ifmix.api.core.modules.antique.ScanResult.Status
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import java.time.Duration
import java.util.UUID

/**
 * 古物扫描业务编排。
 *
 * 流程：
 * 1. 限流检查 → 2. 生成预签名上传 URL → 3. 创建 ScanRecordEntity →
 *    4. 异步调用 ScanRunner → 5. 返回结果
 */
class AntiqueService(
    private val scanRunner: ScanRunner,
    private val objectStorage: ObjectStorage,
    private val rateLimiter: RateLimiter,
    private val mongo: MongoTemplate,
) {

    /**
     * 创建扫描任务。
     *
     * @return 扫描记录 ID
     */
    fun createScan(ctx: RequestContext, request: CreateScanRequest): String {
        // 1. 限流检查（以 appId 作为 subject）
        val subject = ctx.appId
        val limitResult = rateLimiter.check(ctx, subject)
        if (!limitResult.allowed) {
            throw ApiError(
                ErrorCode.RATE_LIMITED,
                "daily limit exceeded",
                mapOf("limit" to limitResult.limit, "count" to limitResult.count),
            )
        }

        // 2. 生成预签名上传 URL
        val objectKey = "antique/${UUID.randomUUID()}.png"
        val uploadUrl = objectStorage.presignUpload(objectKey, "image/png", Duration.ofMinutes(5))

        // 3. 创建扫描记录文档
        val record = ScanRecordEntity().apply {
            appId = ctx.appId
            scanId = UUID.randomUUID().toString()
            imageUrl = uploadUrl
            status = Status.PENDING.name
            tier = "FREE"
            relatedId = request.relatedId
        }
        mongo.insert(record)

        return record.id!!
    }

    /**
     * 获取扫描记录文档（内部方法）。
     */
    fun getScanRecordById(id: String): ScanRecordEntity {
        return mongo.findById(id, ScanRecordEntity::class.java)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "scan record not found")
    }

    /**
     * 获取扫描结果 DTO。
     */
    fun getScanResult(ctx: RequestContext, id: String): ScanDto {
        val record = getScanRecordById(id)
        return ScanDto(
            id = record.id,
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

    /**
     * 游标分页查询扫描记录。
     */
    fun findByCursor(
        ctx: RequestContext,
        input: com.ifmix.api.core.common.db.CursorQueryInput = com.ifmix.api.core.common.db.CursorQueryInput(),
    ): com.ifmix.api.core.common.db.Page<ScanRecordEntity> {
        val query = Query()
        query.addCriteria(Criteria.where("appId").`is`(ctx.appId))
        // 软删过滤
        query.addCriteria(Criteria.where("deletedAt").`is`(null))

        val limit = input.effectiveLimit()
        query.limit(limit + 1)
        val docs = mongo.find(query, ScanRecordEntity::class.java)
        val hasMore = docs.size > limit
        val items = if (hasMore) docs.subList(0, limit) else docs
        return com.ifmix.api.core.common.db.Page(items.toList(), null, hasMore)
    }

    /**
     * 生成预签名上传 URL。
     */
    fun presignedUploadUrl(objectKey: String, contentType: String, duration: Duration): String {
        return objectStorage.presignUpload(objectKey, contentType, duration)
    }

    /**
     * 生成预签名下载 URL。
     */
    fun presignedDownloadUrl(objectKey: String, duration: Duration): String {
        return objectStorage.presignDownload(objectKey, duration)
    }
}
