package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.storage.ObjectStorage
import com.ifmix.api.core.modules.antique.CollectionMembership
import com.ifmix.api.core.modules.antique.ScanDto
import com.ifmix.api.core.modules.antique.ScanMapper
import com.ifmix.api.core.modules.collection.AddItemReq
import com.ifmix.api.core.modules.collection.AddItemRes
import com.ifmix.api.core.modules.collection.CollectionService
import com.ifmix.api.core.modules.collection.GetDefaultRes
import com.ifmix.api.core.modules.collection.ListItemsReq
import com.ifmix.api.core.modules.collection.RemoveItemsReq
import com.ifmix.api.core.modules.collection.RemoveItemsRes
import io.mcarle.konvert.api.Konverter
import jakarta.annotation.Nullable
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * customer BFF 的收藏路由。
 *
 * PUT=query，POST=mutation。返回值由信封 advice 自动包装。
 */
@RestController
@RequestMapping("/customer/core")
class CustomerCollectionController(
    private val service: CollectionService,
    private val storage: ObjectStorage,
) {

    private val scanMapper: ScanMapper = Konverter.get()

    @Autowired
    @Nullable
    private var membership: CollectionMembership? = null

    /** 获取（或创建）默认收藏夹。 */
    @PutMapping("/query/collection/getDefault")
    fun getDefault(ctx: RequestContext): GetDefaultRes {
        val c = service.getDefault(ctx)
        return GetDefaultRes(c.id, c.isDefault, c.createdAt)
    }

    /** 添加收藏条目（幂等）。 */
    @PostMapping("/mutation/collection/addItem")
    fun addItem(ctx: RequestContext, @Valid @RequestBody req: AddItemReq): AddItemRes =
        AddItemRes(service.addItem(ctx, req))

    /** 批量移除收藏条目（软删）。 */
    @PostMapping("/mutation/collection/removeItems")
    fun removeItems(ctx: RequestContext, @Valid @RequestBody req: RemoveItemsReq): RemoveItemsRes =
        RemoveItemsRes(service.removeItems(ctx, req))

    /** 列出收藏夹中的扫描记录（游标分页，join scan_record + presign URL）。 */
    @PutMapping("/query/collection/listItems")
    fun listItems(
        ctx: RequestContext,
        @RequestBody(required = false) req: ListItemsReq?,
    ): Page<ScanDto> {
        val page = service.listItems(ctx, req ?: ListItemsReq())
        return Page(
            page.items.map { scan ->
                val dto = scanMapper.toDto(scan)
                val imageUrl = scan.imageUrl?.let { storage.presignDownload(it, java.time.Duration.ofHours(1)) }
                val collected = membership?.isCollected(ctx, scan.id) ?: true
                dto.copy(imageUrl = imageUrl, collected = collected)
            },
            page.nextCursor,
            page.hasMore,
        )
    }

    companion object {
        /**
         * 控制器 bean 工厂方法：仅在 CollectionService 存在时加载（即 collection 模块已装配）。
         */
        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(CollectionService::class)
        fun customerCollectionController(
            service: CollectionService,
            storage: ObjectStorage,
        ): CustomerCollectionController {
            return CustomerCollectionController(service, storage)
        }
    }
}
