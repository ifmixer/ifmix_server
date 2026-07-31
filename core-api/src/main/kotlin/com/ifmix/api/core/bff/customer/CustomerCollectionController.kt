package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.service.antique.ScanDto
import com.ifmix.api.core.service.collection.AddItemReq
import com.ifmix.api.core.service.collection.AddItemRes
import com.ifmix.api.core.service.collection.CollectionService
import com.ifmix.api.core.service.collection.GetDefaultRes
import com.ifmix.api.core.service.collection.ListItemsReq
import com.ifmix.api.core.service.collection.RemoveItemsReq
import com.ifmix.api.core.service.collection.RemoveItemsRes
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
    @PutMapping("/query/collection/getDefault")
    fun getDefault(ctx: RequestContext): GetDefaultRes {
        val collection = service.getDefault(ctx)
        return GetDefaultRes(id = collection.id.toString(), isDefault = collection.isDefault)
    }

    /** 添加收藏条目（幂等）。 */
    @PostMapping("/mutation/collection/addItem")
    fun addItem(ctx: RequestContext, @Valid @RequestBody req: AddItemReq): AddItemRes =
        service.addItem(ctx, req)

    /** 批量移除收藏条目（软删）。 */
    @PostMapping("/mutation/collection/removeItems")
    fun removeItems(ctx: RequestContext, @Valid @RequestBody req: RemoveItemsReq): RemoveItemsRes =
        service.removeItems(ctx, req)

    /** 列出收藏夹中的扫描记录（游标分页）。 */
    @PutMapping("/query/collection/listItems")
    fun listItems(
        ctx: RequestContext,
        @RequestBody(required = false) req: ListItemsReq?,
    ): Page<ScanDto> {
        val page = service.listItems(ctx, req)
        val dtos = page.items.map { record ->
            ScanDto(
                id = record.id.toString(),
                scanId = record.scanId,
                imageUrl = record.imageUrl,
                status = record.status,
                resultJson = record.resultJson,
                tier = record.tier,
                clientIp = record.clientIp,
                relatedId = record.relatedId,
                createdAt = record.createdAt,
                updatedAt = record.updatedAt,
            )
        }
        return Page(dtos, page.nextCursor, page.hasMore)
    }
}
