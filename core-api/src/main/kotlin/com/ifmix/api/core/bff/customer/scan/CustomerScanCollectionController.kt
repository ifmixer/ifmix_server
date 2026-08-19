package com.ifmix.api.core.bff.customer.scan

import com.ifmix.api.core.entity.scan.dto.ScanCollectionItemDto
import com.ifmix.api.core.infra.dto.Page
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.scan.dto.AddItemReq
import com.ifmix.api.core.modules.scan.dto.AddItemRes
import com.ifmix.api.core.modules.scan.dto.GetDefaultRes
import com.ifmix.api.core.modules.scan.dto.ListItemsReq
import com.ifmix.api.core.modules.scan.dto.RemoveItemsReq
import com.ifmix.api.core.modules.scan.dto.RemoveItemsRes
import com.ifmix.api.core.modules.scan.ScanCollectionFacade
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * customer BFF 的扫描收藏路由。
 */
@RestController
@RequestMapping("/customer")
class CustomerScanCollectionController(
    private val scanCollectionFacade: ScanCollectionFacade,
) {

    /** 获取（或创建）默认收藏夹。 */
    @Operation(summary = "获取默认扫描收藏夹", description = "不存在则自动创建。")
    @PutMapping("/query/core/scan/getDefaultScanCollection")
    fun getDefault(ctx: OperationContext): GetDefaultRes {
        val collection = scanCollectionFacade.getDefault(ctx)
        return GetDefaultRes(
            id = collection.id,
            isDefault = collection.isDefault,
            createdAt = collection.createdAt.toEpochMilli(),
        )
    }

    /** 添加收藏条目（幂等）。 */
    @Operation(summary = "添加扫描记录到收藏（幂等）")
    @PostMapping("/mutation/core/scan/addScanCollectionItem")
    fun addItem(ctx: OperationContext, @Valid @RequestBody req: AddItemReq): AddItemRes =
        scanCollectionFacade.addItem(ctx, req)

    /** 批量移除收藏条目（软删）。 */
    @Operation(summary = "批量移除扫描收藏")
    @PostMapping("/mutation/core/scan/removeScanCollectionItems")
    fun removeItems(ctx: OperationContext, @Valid @RequestBody req: RemoveItemsReq): RemoveItemsRes =
        scanCollectionFacade.removeItems(ctx, req)

    /** 列出收藏夹中的条目（游标分页）。 */
    @Operation(summary = "列出扫描收藏条目")
    @PutMapping("/query/core/scan/findScanCollectionItemsByCursor")
    fun findItemsByCursor(
        ctx: OperationContext,
        @RequestBody(required = false) req: ListItemsReq?,
    ): Page<ScanCollectionItemDto> {
        return scanCollectionFacade.findItemsByCursor(ctx, req)
    }
}
