package com.ifmix.api.core.service.scan

import com.ifmix.api.core.entity.collection.ScanCollection
import com.ifmix.api.core.entity.collection.dto.ScanCollectionItemView
import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.repository.collection.ScanCollectionRepository
import com.ifmix.api.core.repository.collection.ScanCollectionItemRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
open class ScanCollectionService(
    private val collectionRepo: ScanCollectionRepository,
    private val itemRepo: ScanCollectionItemRepository,
) {

    /** 获取或创建用户的默认收藏夹。 */
    @Transactional
    fun getDefault(ctx: OperationContext): ScanCollection {
        val appId = ctx.mustGetAppId()
        val userId = ctx.userId
        val installId = ctx.installId

        return collectionRepo.findDefault(ctx, appId, installId, userId)
            ?: createDefaultCollection(ctx, appId, userId, installId)
    }

    private fun createDefaultCollection(
        ctx: OperationContext,
        appId: UUID,
        userId: UUID?,
        installId: UUID?,
    ): ScanCollection {
        val now = Instant.now()
        val entity = ScanCollection {
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

    /** 添加扫描记录到收藏（幂等）。 */
    @Transactional
    fun addItem(ctx: OperationContext, req: AddItemReq): AddItemRes {
        val collectionId = req.collectionId ?: getDefault(ctx).id
        val itemId = itemRepo.insertIfAbsent(ctx, ctx.mustGetAppId(), collectionId, req.scanRecordId)
        return AddItemRes(id = itemId)
    }

    /** 批量从收藏中移除扫描记录（软删除）。 */
    @Transactional
    fun removeItems(ctx: OperationContext, req: RemoveItemsReq): RemoveItemsRes {
        if (req.scanRecordIds.isEmpty()) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "scanRecordIds cannot be empty")
        }
        val appId = ctx.mustGetAppId()
        val collectionId = req.collectionId ?: getDefault(ctx).id
        val deletedCount = itemRepo.softDeleteByScanIds(ctx, appId, collectionId, req.scanRecordIds)
        return RemoveItemsRes(removed = deletedCount.toInt())
    }

    /** 列出收藏中的条目（游标分页）。 */
    @Transactional(readOnly = true)
    fun findItemsByCursor(ctx: OperationContext, req: ListItemsReq?): Page<ScanCollectionItemView> {
        val appId = ctx.mustGetAppId()
        val collectionId = req?.collectionId ?: getDefault(ctx).id
        val limit = req?.limit ?: 20
        val cursor = req?.cursor?.let { try { UUID.fromString(it) } catch (_: Exception) { null } }
        return itemRepo.findItemsByCursor(ctx, appId, collectionId, limit, cursor)
    }
}

// Request/Response DTOs
data class AddItemReq(val collectionId: UUID? = null, val scanRecordId: UUID)
data class AddItemRes(val id: UUID)
data class RemoveItemsReq(val collectionId: UUID? = null, val scanRecordIds: List<UUID>)
data class RemoveItemsRes(val removed: Int)
data class ListItemsReq(val collectionId: UUID? = null, val limit: Int? = null, val cursor: String? = null)
data class GetDefaultRes(val id: UUID, val isDefault: Boolean, val createdAt: Long?)
