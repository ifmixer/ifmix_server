package com.ifmix.core.api.dto.common

import java.util.UUID

/**
 * 与 schema/common/common.graphqls 的 CommonFindOptions 字段一一对应（RPC 入参侧）。
 * sortDirection 为字符串透传（"ASC"/"DESC"，大小写敏感），由 mapper 校验后转 generated 枚举。
 */
data class CommonFindOptions(
    val filter: FilterGroup? = null,
    val cursor: String? = null,
    val sortBy: String? = null,
    val sortDirection: String? = null,   // "ASC" | "DESC"
    val limit: Int? = null,
)
