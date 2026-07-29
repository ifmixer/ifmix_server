package com.ifmix.api.core.service.collection

import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.repository.collection.CollectionItemRepository
import com.ifmix.api.core.repository.collection.CollectionRepository
import com.ifmix.api.core.service.antique.CollectionMembership
import java.util.UUID

/**
 * 完整的收藏归属实现 - 检查扫描记录是否在用户的默认收藏夹中。
 */
class CollectionMembershipImpl(
    private val itemRepo: CollectionItemRepository,
    private val collectionRepo: CollectionRepository,
) : CollectionMembership {

    override fun isCollected(ctx: RequestContext, scanRecordId: String): Boolean {
        try {
            val scanRecordIdUUID = UUID.fromString(scanRecordId)
            val appId = requireNonNullCtxAppId(ctx)
            val collection = collectionRepo.findDefault(appId, ctx.installId, ctx.userId) ?: return false
            return itemRepo.existsByScanRecordId(collection.id, scanRecordIdUUID)
        } catch (e: Exception) {
            return false
        }
    }

    private fun requireNonNullCtxAppId(ctx: RequestContext): UUID {
        return try {
            UUID.fromString(ctx.appId)
        } catch (e: Exception) {
            throw RuntimeException("Invalid app ID in context")
        }
    }
}
