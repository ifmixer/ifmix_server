package com.ifmix.api.core.modules.ai.handler

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.ai.AiFacade
import com.ifmix.api.core.modules.ai.entity.CollectionEntity

/**
 * CollectionEntity 数据访问处理层。
 *
 * 封装所有针对 collection 集合的 CRUD 操作，供 [AiFacade] 调用。
 */
class CollectionEntityHandler(private val facade: AiFacade) {

    /** 获取（或创建）默认收藏夹。 */
    fun getDefault(ctx: RequestContext): CollectionEntity = facade.getDefaultCollection(ctx)
}
