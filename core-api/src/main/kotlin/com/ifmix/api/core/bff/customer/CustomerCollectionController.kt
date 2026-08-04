package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.entity.collection.dto.CollectionItemView
import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.service.collection.AddItemReq
import com.ifmix.api.core.service.collection.AddItemRes
import com.ifmix.api.core.service.collection.CollectionService
import com.ifmix.api.core.service.collection.GetDefaultRes
import com.ifmix.api.core.service.collection.ListItemsReq
import com.ifmix.api.core.service.collection.RemoveItemsReq
import com.ifmix.api.core.service.collection.RemoveItemsRes
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * customer BFF 的收藏路由。
 */
@RestController
@RequestMapping("/customer/core")
class CustomerCollectionController(
    private val service: CollectionService,
) {

    /** 获取（或创建）默认收藏夹。 */
    @Operation(summary = "获取默认收藏夹", description = "不存在则自动创建。当前只支持单个默认收藏夹。")
    @PutMapping("/query/collection/getDefault")
    fun getDefault(ctx: OperationContext): GetDefaultRes {
        val collection = service.getDefault(ctx)
        return GetDefaultRes(
            id = collection.id,
            isDefault = collection.isDefault,
            createdAt = collection.createdAt.toEpochMilli(),
        )
    }

    /** 添加收藏条目（幂等）。 */
    @Operation(summary = "添加收藏（幂等）", description = "scanRecordId 为扫描记录 ID（UUIDv7），必须属于当前用户，否则返回 404。重复添加不会报错。")
    @PostMapping("/mutation/collection/addItem")
    fun addItem(ctx: OperationContext, @Valid @RequestBody req: AddItemReq): AddItemRes =
        service.addItem(ctx, req)

    /** 批量移除收藏条目（软删）。 */
    @Operation(summary = "批量移除收藏", description = "scanRecordIds 为扫描记录 ID 数组，必须属于当前用户。")
    @PostMapping("/mutation/collection/removeItems")
    fun removeItems(ctx: OperationContext, @Valid @RequestBody req: RemoveItemsReq): RemoveItemsRes =
        service.removeItems(ctx, req)

    /** 列出收藏夹中的条目（游标分页）。 */
    @Operation(summary = "列出收藏夹中的条目", description = "按当前用户过滤。固定按 createdAt DESC 排序，不支持自定义排序。limit 默认 20，上限 100。body 完全可选。collectionId 当前可不传，服务端自动用默认夹。")
    @PutMapping("/query/collection/findItemsByCursor")
    fun findItemsByCursor(
        ctx: OperationContext,
        @RequestBody(required = false) req: ListItemsReq?,
    ): Page<CollectionItemView> {
        return service.findItemsByCursor(ctx, req)
    }
}
