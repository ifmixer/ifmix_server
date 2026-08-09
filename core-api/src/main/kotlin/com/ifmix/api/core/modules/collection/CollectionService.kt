package com.ifmix.api.core.modules.collection

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.db.ownsRow
import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.service.CRUDService
import com.ifmix.api.core.modules.antique.ScanRecordDocument
import org.springframework.dao.DuplicateKeyException

/**
 * 收藏业务编排。
 *
 * 组合持有 collection 的 CRUDService + CollectionItemRepository + CollectionRepository，
 * 提供默认夹 get-or-create、添加/移除条目、列表查询等功能。
 */
class CollectionService(
    private val collectionCrud: CRUDService<CollectionDocument>,
    private val itemRepo: CollectionItemRepository,
    private val collectionRepo: CollectionRepository,
) {

    /**
     * 获取（或创建）默认收藏夹。
     *
     * partial unique 约束兜底并发冲突：若另一请求先创建了默认夹，捕获 DuplicateKeyException
     * 后回读已有默认夹。
     */
    fun getDefault(ctx: RequestContext): CollectionDocument {
        collectionRepo.findDefault(ctx)?.let { return it }

        val doc = CollectionDocument().apply {
            installId = ctx.installId
            userId = ctx.userId
            isDefault = true
        }
        return try {
            collectionCrud.createOne(ctx, doc)
            doc
        } catch (e: DuplicateKeyException) {
            // 并发创建冲突：partial unique 兜底，回读（primaryPreferred 保证写后回读主库）
            collectionRepo.findDefault(ctx)
                ?: throw ApiError(ErrorCode.INTERNAL, "failed to create default collection")
        }
    }

    /**
     * 解析 collectionId：null → 默认夹；指定 id → 验证归属。
     */
    private fun resolveCollectionId(ctx: RequestContext, collectionId: String?): String {
        if (collectionId == null) return getDefault(ctx).id
        val coll = collectionCrud.findById(ctx, collectionId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "collection not found")
        if (!ownsRow(ctx, coll.userId, coll.installId)) {
            throw ApiError(ErrorCode.NOT_FOUND, "collection not found")
        }
        return coll.id
    }

    /**
     * 添加收藏条目。幂等：已存在则返回已有 id。
     */
    fun addItem(ctx: RequestContext, req: AddItemReq): String {
        val cid = resolveCollectionId(ctx, req.collectionId)
        return itemRepo.insertIfAbsent(ctx, cid, req.scanRecordId!!)
    }

    /**
     * 批量移除收藏条目（软删）。
     */
    fun removeItems(ctx: RequestContext, req: RemoveItemsReq): Long {
        val cid = resolveCollectionId(ctx, req.collectionId)
        return itemRepo.softDeleteByScanIds(ctx, cid, req.scanRecordIds!!)
    }

    /**
     * 列出收藏夹中的扫描记录（游标分页，join scan_record）。
     */
    fun listItems(ctx: RequestContext, req: ListItemsReq): Page<ScanRecordDocument> {
        val cid = resolveCollectionId(ctx, req.collectionId)
        val limit = (req.limit ?: CursorQueryInput.DEFAULT_LIMIT).coerceIn(1, CursorQueryInput.MAX_LIMIT)
        val (scans, next) = itemRepo.listScanRecords(ctx, cid, req.cursor, limit)
        return Page(scans, next, next != null)
    }
}
