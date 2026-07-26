package com.ifmix.api.core.modules.antique

import com.ifmix.api.core.common.http.RequestContext

/**
 * 收藏归属端口：scanRecordId 是否在调用方的默认收藏夹中。
 *
 * 放在 antique 包内，避免 antique import collection 造成循环依赖。
 * collection 模块实现此接口并注册为 Spring bean，antique 通过 ObjectProvider 可选注入。
 */
fun interface CollectionMembership {

    /**
     * 判断扫描记录是否已被收藏（在默认夹中）。
     *
     * @param ctx 请求上下文（含 appId、userId/installId）
     * @param scanRecordId 扫描记录 ID（scan_record._id hex）
     * @return 是否已收藏
     */
    fun isCollected(ctx: RequestContext, scanRecordId: String): Boolean
}
