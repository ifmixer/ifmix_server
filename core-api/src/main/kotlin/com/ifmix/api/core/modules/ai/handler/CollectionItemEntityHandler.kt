package com.ifmix.api.core.modules.ai.handler
import org.springframework.stereotype.Component

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.ai.AiFacade
import com.ifmix.api.core.modules.ai.entity.CollectionItemEntity
import com.ifmix.api.core.modules.ai.entity.ScanRecordEntity

/**
 * CollectionItemEntity 数据访问处理层。
 *
 * 封装所有针对 collection_item 集合的操作（含 join scan_record 的复合查询），
 * 供 [AiFacade] 调用。
 */
@Component
class CollectionItemEntityHandler(private val facade: AiFacade) {

    /** 添加收藏条目（幂等插入）。 */
    fun addItem(ctx: RequestContext, collectionId: String, scanRecordId: String): String =
        facade.addItem(ctx, collectionId, scanRecordId)

    /** 批量移除收藏条目（软删）。 */
    fun removeItems(ctx: RequestContext, collectionId: String, scanRecordIds: List<String>): Long =
        facade.removeItems(ctx, collectionId, scanRecordIds)

    /**
     * 列出收藏条目文档（含关联的 scan record），用于构建 GraphQL CollectionItemType。
     *
     * @return Triple<item列表, scanRecordId→scan记录映射, hasMore>
     */
    fun listItemsWithRecords(
        ctx: RequestContext,
        collectionId: String?,
        cursor: String?,
        limit: Int,
    ): Triple<List<CollectionItemEntity>, Map<String, ScanRecordEntity>, Boolean> =
        facade.listItemsWithRecords(ctx, collectionId, cursor, limit)
}
