package com.ifmix.core.api.dto.cs

import java.util.UUID

/** 工单详情协议入参（原 GraphQL 裸参数 `id: UUID!` 的 wire 包装）。 */
data class SupportRequestByIdInput(
    val id: UUID,
)
