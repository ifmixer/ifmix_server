package com.ifmix.api.core.modules.ai

import com.ifmix.api.core.common.db.CRUDOps
import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.db.ownsRow
import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.ratelimit.RateLimiter
import com.ifmix.api.core.common.storage.ObjectStorage
import com.ifmix.api.core.modules.ai.entity.CollectionEntity
import com.ifmix.api.core.modules.ai.entity.CollectionItemEntity
import com.ifmix.api.core.modules.ai.entity.ScanRecordEntity
import com.ifmix.api.core.modules.ai.repo.CollectionItemRepository
import com.ifmix.api.core.modules.ai.repo.CollectionRepository
import com.ifmix.api.core.modules.ai.repo.ScanRecordRepository
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.isEqualTo
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * AI 模块门面，合并古物扫描（antique）与收藏（collection）业务。
 *
 * 取代原来的 AntiqueService + CollectionService，提供统一的扫描和收藏操作编排。
 *
 * 流程：
 * 1. 限流检查 → 2. 生成预签名上传 URL → 3. 创建 ScanRecordEntity →
 *    4. 异步调用 ScanRunner → 5. 返回结果
 *
 * CollectionMembership 可选注入，未注入时 scanned 结果中 collected 恒为 false。
 */
class AiFacade(
    private val scanRunner: ScanRunner,
    private val objectStorage: ObjectStorage,
    private val rateLimiter: RateLimiter,
    private val mongo: MongoTemplate,
    private val scanRecordRepo: ScanRecordRepository,
    private val collectionCrudOps: CRUDOps<CollectionEntity>,
    private val collectionRepo: CollectionRepository,
    private val collectionItemRepo: CollectionItemRepository,
    private val collectionMembership: org.springframework.beans.factory.ObjectProvider<CollectionMembership>,
) {

    // ---- scan 操作 ----

    /** 创建扫描任务。 */
    fun createScan(ctx: RequestContext, request: CreateScanRequest): String {
        val subject = ctx.appId
        val limitResult = rateLimiter.check(ctx, subject)
        if (!limitResult.allowed) {
            throw ApiError(
                ErrorCode.RATE_LIMITED,
                "daily limit exceeded",
                mapOf("limit" to limitResult.limit, "count" to limitResult.count),
            )
        }

        val objectKey = "antique/${UUID.randomUUID()}.png"
        val uploadUrl = objectStorage.presignUpload(objectKey, "image/png", Duration.ofMinutes(5))

        val record = ScanRecordEntity().apply {
            appId = ObjectId(ctx.appId)
            scanId = UUID.randomUUID().toString()
            imageUrl = uploadUrl
            status = ScanResult.Status.PENDING.name
            tier = "FREE"
            relatedId = request.relatedId
        }
        mongo.insert(record)
        return record.id!!.toHexString()
    }

    /** 按 id 获取扫描记录。 */
    fun getScanRecordById(ctx: RequestContext, id: String): ScanRecordEntity {
        return scanRecordRepo.findById(id)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "scan record not found")
    }

    /** 游标分页查询扫描记录。 */
    fun findByCursor(
        ctx: RequestContext,
        cursor: String? = null,
        limit: Int? = null,
        collected: Boolean? = null,
    ): Page<ScanRecordEntity> {
        val input = CursorQueryInput(cursor = cursor, limit = limit)
        val page = scanRecordRepo.findByCursor(ctx, input)
        // 若指定 collected 过滤，则在内存中过滤
        val filtered = if (collected != null) {
            page.items.filter { it.collected == collected }
        } else page.items
        val hasMoreFiltered = filtered.size < page.items.size || page.hasMore
        return Page(filtered, page.nextCursor, hasMoreFiltered)
    }

    /** 标记扫描记录为已收藏（或取消收藏），best-effort。 */
    fun markCollected(ctx: RequestContext, scanRecordId: String, collected: Boolean) {
        val update = Update().set(ScanRecordEntity::collected, collected).set(ScanRecordEntity::updatedAt, Instant.now())
        mongo.updateFirst(
            Query(Criteria().andOperator(
                ScanRecordEntity::id isEqualTo ObjectId(scanRecordId),
                ScanRecordEntity::appId isEqualTo ctx.appId,
            )),
            update,
            ScanRecordEntity::class.java,
        )
    }

    /** 按 id 列表批量查询扫描记录。 */
    fun findByIds(ctx: RequestContext, ids: List<String>): List<ScanRecordEntity> =
        scanRecordRepo.findByIds(ctx, ids)

    /** 预签名上传 URL。 */
    fun presignedUploadUrl(objectKey: String, contentType: String, duration: Duration): String =
        objectStorage.presignUpload(objectKey, contentType, duration)

    /** 预签名下载 URL。 */
    fun presignedDownloadUrl(objectKey: String, duration: Duration): String =
        objectStorage.presignDownload(objectKey, duration)

    /** 软删扫描记录。 */
    fun deleteScan(ctx: RequestContext, id: String): Boolean = scanRecordRepo.deleteById(ctx, id)

    // ---- collection 操作 ----

    /** 获取（或创建）默认收藏夹。 */
    fun getDefaultCollection(ctx: RequestContext): CollectionEntity {
        collectionRepo.findDefault(ctx)?.let { return it }

        val doc = CollectionEntity().apply {
            installId = ctx.installId
            userId = ctx.userId
            isDefault = true
        }
        return try {
            collectionCrudOps.insertOne(com.ifmix.api.core.common.db.RepoCtx(), ctx.appId, doc)
            doc
        } catch (e: org.springframework.dao.DuplicateKeyException) {
            collectionRepo.findDefault(ctx)
                ?: throw ApiError(ErrorCode.INTERNAL, "failed to create default collection")
        }
    }

    /** 解析 collectionId：null → 默认夹；指定 id → 验证归属。 */
    private fun resolveCollectionId(ctx: RequestContext, collectionId: String?): String {
        if (collectionId == null) return getDefaultCollection(ctx).id!!.toHexString()
        val coll = collectionCrudOps.findById(
            com.ifmix.api.core.common.db.RepoCtx(), ctx.appId, collectionId,
        ) ?: throw ApiError(ErrorCode.NOT_FOUND, "collection not found")
        if (!ownsRow(ctx, coll.userId, coll.installId)) {
            throw ApiError(ErrorCode.NOT_FOUND, "collection not found")
        }
        return coll.id!!.toHexString()
    }

    /** 添加收藏条目（幂等插入）。 */
    fun addItem(ctx: RequestContext, collectionId: String?, scanRecordId: String): String {
        val cid = resolveCollectionId(ctx, collectionId)
        val itemId = collectionItemRepo.insertIfAbsent(ctx, cid, scanRecordId)
        try { markCollected(ctx, scanRecordId, true) } catch (_: Exception) { }
        return itemId
    }

    /** 批量移除收藏条目（软删）。 */
    fun removeItems(ctx: RequestContext, collectionId: String?, scanRecordIds: List<String>): Long {
        val cid = resolveCollectionId(ctx, collectionId)
        return collectionItemRepo.softDeleteByScanIds(ctx, cid, scanRecordIds)
    }

    /**
     * 列出收藏条目文档（含关联的 scan record），用于构建 GraphQL CollectionItemType。
     *
     * @return Triple<item列表, scanRecordId→scan记录映射, hasMore>
     */
    fun listItemsWithRecords(
        ctx: RequestContext,
        collectionId: String?,
        cursor: String?,
        limit: Int,
    ): Triple<List<CollectionItemEntity>, Map<String, ScanRecordEntity>, Boolean> {
        val cid = resolveCollectionId(ctx, collectionId)
        val effectiveLimit = limit.coerceIn(CursorQueryInput.DEFAULT_LIMIT, CursorQueryInput.MAX_LIMIT)
        val items = collectionItemRepo.findItemsByCursor(ctx, cid, cursor, effectiveLimit)
        val hasMore = items.size > effectiveLimit
        val pageItems = if (hasMore) items.dropLast(1) else items

        val scanIds = pageItems.mapNotNull { it.scanRecordId?.toHexString() }.distinct()
        val scanMap = if (scanIds.isNotEmpty()) {
            findByIds(ctx, scanIds).associateBy { it.id!!.toHexString() }
        } else emptyMap()

        return Triple(pageItems, scanMap, hasMore)
    }
}
