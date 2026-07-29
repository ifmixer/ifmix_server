package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.infra.storage.ObjectStorage
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
 * customer BFF 的收藏路由（简化版 - 待完善）。
 */
@RestController
@RequestMapping("/customer/core")
class CustomerCollectionController(
    private val service: CollectionService,
    private val storage: ObjectStorage,
) {

    private var membership: com.ifmix.api.core.service.antique.CollectionMembership? = null

    /** 获取（或创建）默认收藏夹。 */
    @PutMapping("/query/collection/getDefault")
    fun getDefault(ctx: RequestContext): GetDefaultRes = throw NotImplementedError("getDefault not implemented")

    /** 添加收藏条目（幂等）。 */
    @PostMapping("/mutation/collection/addItem")
    fun addItem(ctx: RequestContext, @Valid @RequestBody req: AddItemReq): AddItemRes = throw NotImplementedError("addItem not implemented")

    /** 批量移除收藏条目（软删）。 */
    @PostMapping("/mutation/collection/removeItems")
    fun removeItems(ctx: RequestContext, @Valid @RequestBody req: RemoveItemsReq): RemoveItemsRes = throw NotImplementedError("removeItems not implemented")

    /** 列出收藏夹中的扫描记录（游标分页）。 */
    @PutMapping("/query/collection/listItems")
    fun listItems(
        ctx: RequestContext,
        @RequestBody(required = false) req: ListItemsReq?,
    ): Page<com.ifmix.api.core.service.antique.ScanDto> {
        return Page(emptyList(), null, false)
    }
}
