package com.ifmix.api.core.modules.collection

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.Instant

/** 获取默认收藏夹响应。 */
data class GetDefaultRes(
    val id: String?,
    val isDefault: Boolean,
    val createdAt: Instant?,
)

/** 添加收藏条目请求。 */
data class AddItemReq(
    val collectionId: String? = null,       // 省略则使用默认夹
    @field:NotBlank(message = "scanRecordId is required")
    val scanRecordId: String? = null,
)

/** 添加收藏条目响应。 */
data class AddItemRes(
    val id: String?,
)

/** 批量移除收藏条目请求。 */
data class RemoveItemsReq(
    val collectionId: String? = null,
    @field:NotEmpty(message = "scanRecordIds must not be empty")
    @field:Size(max = 100, message = "max 100 items at once")
    val scanRecordIds: List<String>? = null,
)

/** 批量移除收藏条目响应。 */
data class RemoveItemsRes(
    val removed: Long,
)

/** 列出收藏条目请求（游标分页）。 */
data class ListItemsReq(
    val collectionId: String? = null,
    val cursor: String? = null,
    val limit: Int? = null,
)

// listItems 响应复用 antique 的 ScanDto，经 Page<ScanDto> 返回
