package com.ifmix.api.core.service.collection

import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.appIdAsUUID
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

    override fun isCollected(ctx: OperationContext, scanRecordId: String): Boolean {
        try {
            val scanRecordIdUUID = UUID.fromString(scanRecordId)
            val appId = ctx.appIdAsUUID()
            val collection = collectionRepo.findDefault(ctx.repo, appId, ctx.installId, ctx.userId) ?: return false
            return itemRepo.existsByScanRecordId(ctx.repo, collection.id, scanRecordIdUUID)
        } catch (e: Exception) {
            return false
        }
    }
}
