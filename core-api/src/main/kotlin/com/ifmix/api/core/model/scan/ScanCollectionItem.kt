package com.ifmix.api.core.model.scan

import java.time.Instant
import java.util.UUID

data class ScanCollectionItem(
    val id: UUID,
    val appId: UUID,
    val collectionId: UUID,
    val scanRecordId: UUID,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val deletedAt: Instant? = null,
)
