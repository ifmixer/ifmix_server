package com.ifmix.api.core.model.scan

import java.time.Instant
import java.util.UUID

data class ScanCollection(
    val id: UUID,
    val appId: UUID,
    val installId: UUID? = null,
    val userId: UUID? = null,
    val isDefault: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val deletedAt: Instant? = null,
)
