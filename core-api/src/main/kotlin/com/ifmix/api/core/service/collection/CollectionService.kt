package com.ifmix.api.core.service.collection

import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.entity.collection.dto.CollectionItemView
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
    fun getDefault(ctx: OperationContext): Collection {
        val appId = ctx.mustGetAppId()
        val userId = ctx.userId
        val installId = ctx.installId

        var collection = collectionRepo.findDefault(ctx, appId, installId, userId)

        if (collection == null) {
            collection = createDefaultCollection(ctx, appId, userId, installId)
        }

        return collection
    }

    private fun createDefaultCollection(
        ctx: OperationContext,
        appId: UUID,
        userId: UUID?,
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
        return collectionRepo.save(ctx, entity)
    }

    /**
     * 添加扫描记录到收藏。
     * 幂等操作：重复添加不会创建重复项。
     */
    @Transactional
    fun addItem(ctx: OperationContext, req: AddItemReq): AddItemRes {
        val collectionId = req.collectionId ?: getDefault(ctx).id

        val itemId = itemRepo.insertIfAbsent(ctx, ctx.mustGetAppId(), collectionId, req.scanRecordId)
        return AddItemRes(id = itemId)
    }

    /**
     * 批量从收藏中移除扫描记录（软删除）。
     */
    @Transactional
    fun removeItems(ctx: OperationContext, req: RemoveItemsReq): RemoveItemsRes {
        if (req.scanRecordIds.isEmpty()) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "scanRecordIds cannot be empty")
        }

        val appId = ctx.mustGetAppId()
        val collectionId = req.collectionId ?: getDefault(ctx).id

        val deletedCount = itemRepo.softDeleteByScanIds(ctx, appId, collectionId, req.scanRecordIds)
        return RemoveItemsRes(deletedCount.toInt())
    }

    /**
     * 列出收藏中的扫描记录，带分页和游标支持。
     * 返回包含扫描详情的 Page<ScanRecord>。
     */
    @Transactional
    fun findItemsByCursor(ctx: OperationContext, req: ListItemsReq?): Page<CollectionItemView> {
        val appId = ctx.mustGetAppId()

        val collectionId = req?.collectionId ?: getDefault(ctx).id
        val limit = req?.limit ?: 20
        val cursor = req?.cursor?.let { try { UUID.fromString(it) } catch (e: Exception) { null } }

        return itemRepo.findItemsByCursor(ctx, appId, collectionId, limit, cursor)
    }
}

// Request/Response DTOs
data class AddItemReq(val collectionId: UUID? = null, val scanRecordId: UUID)  // scanRecordId 必填
data class AddItemRes(val id: UUID)  // id 对应 collection_item 表的 id

data class RemoveItemsReq(val collectionId: UUID? = null, val scanRecordIds: List<UUID>)  // 必填非空列表
data class RemoveItemsRes(val removed: Int)

data class ListItemsReq(val collectionId: UUID? = null, val limit: Int? = null, val cursor: String? = null)

data class GetDefaultRes(val id: UUID, val isDefault: Boolean, val createdAt: Long?)  // 补回 createdAt
