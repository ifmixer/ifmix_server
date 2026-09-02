package com.ifmix.core.api.dto.ai

import java.util.UUID

data class AddItemReq(val collectionId: UUID? = null, val scanRecordId: UUID)
data class AddItemRes(val id: UUID)
data class RemoveItemsReq(val collectionId: UUID? = null, val scanRecordIds: List<UUID>)
data class RemoveItemsRes(val removed: Int)
data class ListItemsReq(val collectionId: UUID? = null, val limit: Int? = null, val cursor: String? = null)
data class GetDefaultRes(val id: UUID, val isDefault: Boolean, val createdAt: Long?)
