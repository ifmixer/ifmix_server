package com.ifmix.api.core.service.collection

import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.infra.http.appIdAsUUID
import com.ifmix.api.core.entity.antique.ScanRecord
import com.ifmix.api.core.entity.collection.Collection
import com.ifmix.api.core.repository.collection.CollectionRepository
import com.ifmix.api.core.repository.collection.CollectionItemRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.ifmix.api.core.infra.db.UuidV7
import java.time.Instant
import java.util.UUID

/**
 * 收藏业务编排 - 完整实现。
 */
@Service
open class CollectionService(
    private val collectionRepo: CollectionRepository,
    private val itemRepo: CollectionItemRepository,
) {

    /**
     * 获取或创建用户的默认收藏。
     * 按 userId 优先匹配，若无 userId 则按 installId 匹配。
     */
    @Transactional
    fun getDefault(ctx: RequestContext): Collection {
        val appId = ctx.appIdAsUUID()
        val userId = ctx.userId
        val installId = ctx.installId

        var collection = collectionRepo.findDefault(appId, installId, userId)

        if (collection == null) {
            collection = createDefaultCollection(appId, userId, installId)
        }

        return collection
    }

    private fun createDefaultCollection(
        appId: UUID,
        userId: String?,
        installId: UUID?,
    ): Collection {
        val now = Instant.now()
        val entity = Collection {
            id = UuidV7.generate()
            this.appId = appId
            this.userId = userId
            this.installId = installId
            this.isDefault = true
            createdAt = now
            updatedAt = now
            deletedAt = null
        }
        return collectionRepo.save(entity)
    }

    /**
     * 添加扫描记录到收藏。
     * 幂等操作：重复添加不会创建重复项。
     */
    @Transactional
    fun addItem(ctx: RequestContext, req: AddItemReq): AddItemRes {
        val scanRecordIdUUID = try {
            UUID.fromString(req.scanRecordId)
        } catch (e: Exception) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "Invalid scanRecordId format")
        }

        val collectionId = when {
            req.collectionId != null -> UUID.fromString(req.collectionId)
            else -> getDefault(ctx).id
        }

        val itemId = itemRepo.insertIfAbsent(ctx.appIdAsUUID(), collectionId, scanRecordIdUUID)
        return AddItemRes(id = itemId.toString())
    }

    /**
     * 批量从收藏中移除扫描记录（软删除）。
     */
    @Transactional
    fun removeItems(ctx: RequestContext, req: RemoveItemsReq): RemoveItemsRes {
        if (req.scanRecordIds.isEmpty()) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "scanRecordIds cannot be empty")
        }

        val parsedIds = req.scanRecordIds.map { id ->
            try {
                UUID.fromString(id)
            } catch (e: Exception) {
                throw ApiError(ErrorCode.INVALID_REQUEST, "Invalid scanRecordId format: $id")
            }
        }

        val appId = ctx.appIdAsUUID()
        val collectionId = when {
            req.collectionId != null -> UUID.fromString(req.collectionId)
            else -> getDefault(ctx).id
        }

        val deletedCount = itemRepo.softDeleteByScanIds(appId, collectionId, parsedIds)
        return RemoveItemsRes(deletedCount.toInt())
    }

    /**
     * 列出收藏中的扫描记录，带分页和游标支持。
     * 返回包含扫描详情的 Page<ScanRecord>。
     */
    @Transactional
    fun listItems(ctx: RequestContext, req: ListItemsReq?): Page<ScanRecord> {
        val appId = ctx.appIdAsUUID()

        val collectionId = when {
            req?.collectionId != null -> UUID.fromString(req.collectionId)
            else -> getDefault(ctx).id
        }

        val limit = req?.limit ?: 20
        val cursor = req?.cursor?.let { try { UUID.fromString(it) } catch (e: Exception) { null } }

        val collectionItems = itemRepo.listWithScanRecords(appId, collectionId, limit, cursor)
        val scanRecords = collectionItems.items.mapNotNull { item -> item.scanRecord }

        val nextCursor = if (collectionItems.hasMore && collectionItems.items.isNotEmpty()) {
            collectionItems.items.lastOrNull()?.id?.toString()
        } else null

        return Page(scanRecords, nextCursor, collectionItems.hasMore)
    }
}

// Request/Response DTOs
data class AddItemReq(val collectionId: String? = null, val scanRecordId: String)  // scanRecordId 必填
data class AddItemRes(val id: String)  // id 对应 collection_item 表的 id

data class RemoveItemsReq(val collectionId: String? = null, val scanRecordIds: List<String>)  // 必填非空列表
data class RemoveItemsRes(val removed: Int)

data class ListItemsReq(val collectionId: String? = null, val limit: Int? = null, val cursor: String? = null)

data class GetDefaultRes(val id: String, val isDefault: Boolean, val createdAt: Long?)  // 补回 createdAt
