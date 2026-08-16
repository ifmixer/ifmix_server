package com.ifmix.api.core.modules.antique

import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.ratelimit.RateLimiter
import com.ifmix.api.core.common.storage.ObjectStorage
import com.ifmix.api.core.modules.antique.ScanResult.Status
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 古物扫描业务编排。
 *
 * 流程：
 * 1. 限流检查 → 2. 生成预签名上传 URL → 3. 创建 ScanRecordDocument →
 *    4. 异步调用 ScanRunner → 5. 返回结果
 */
class AntiqueService(
    private val scanRunner: ScanRunner,
    private val objectStorage: ObjectStorage,
    private val rateLimiter: RateLimiter,
    private val mongo: MongoTemplate,
    private val scanRecordRepo: ScanRecordRepository,
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
        val record = ScanRecordDocument().apply {
            appId = ObjectId(ctx.appId)
            scanId = UUID.randomUUID().toString()
            imageUrl = uploadUrl
            status = Status.PENDING.name
            tier = "FREE"
            relatedId = request.relatedId
        }
        mongo.insert(record)

        return record.id.toHexString()
    }

    /**
     * 获取扫描记录文档（内部方法）。
     */
    fun getScanRecordById(id: String): ScanRecordDocument {
        return mongo.findById(id, ScanRecordDocument::class.java)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "scan record not found")
    }

    /**
     * 获取扫描结果 DTO。
     */
    fun getScanResult(ctx: RequestContext, id: String): ScanDto {
        val record = getScanRecordById(id)
        return ScanDto(
            id = record.id.toHexString(),
            scanId = record.scanId,
            imageUrl = record.imageUrl,
            status = record.status,
            resultJson = record.resultJson,
            tier = record.tier,
            clientIp = record.clientIp,
            relatedId = record.relatedId,
            userId = record.userId,
            installId = record.installId,
            collected = record.collected,
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
    ): com.ifmix.api.core.common.db.Page<ScanRecordDocument> {
        val query = Query()
        query.addCriteria(Criteria.where("appId").`is`(ctx.appId))
        // 软删过滤
        query.addCriteria(Criteria.where("deletedAt").`is`(null))

        val limit = input.effectiveLimit()
        query.limit(limit + 1)
        val docs = mongo.find(query, ScanRecordDocument::class.java)
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

    /**
     * 将扫描记录标记为已收藏（或取消收藏）。
     *
     * best-effort 语义：异常时静默忽略，不影响主流程。
     */
    fun markCollected(ctx: RequestContext, scanRecordId: String, collected: Boolean) {
        val update = Update().set("collected", collected).set("updatedAt", Instant.now())
        mongo.updateFirst(
            Query(Criteria.where("_id").`is`(org.bson.types.ObjectId(scanRecordId))
                .and("appId").`is`(ctx.appId)),
            update,
            ScanRecordDocument::class.java,
        )
    }

    /** 按 id 列表批量查询扫描记录（委托给 scanRecordRepo）。 */
    fun findByIds(ctx: RequestContext, ids: List<String>): List<ScanRecordDocument> =
        scanRecordRepo.findByIds(ctx, ids)
}
