package com.ifmix.core.api.dto.ai

import java.util.UUID

data class ScanStatusSnapshot(
    val scanId: UUID,
    val status: Int,
    val errorCode: String?,
)
