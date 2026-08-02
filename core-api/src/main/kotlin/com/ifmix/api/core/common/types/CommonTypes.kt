package com.ifmix.api.core.common.types

import java.util.UUID

/** 通用 ID 请求体 */
data class ByIdRequest(val id: UUID)

/** 通用操作结果 */
data class OperationResult(val success: Boolean = true)
