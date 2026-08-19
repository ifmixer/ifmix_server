package com.ifmix.api.core.modules.ai.handler

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.ai.AiFacade
import com.ifmix.api.core.modules.ai.entity.ScanRecordEntity

/**
 * ScanRecordEntity 数据访问处理层。
 *
 * 封装所有针对 scan_records 集合的 CRUD 操作，供 [AiFacade] 调用。
 */
class ScanRecordEntityHandler(private val facade: AiFacade) {

    /** 按 id 获取扫描记录（未命中抛 NOT_FOUND）。 */
    fun getById(ctx: RequestContext, id: String): ScanRecordEntity =
        facade.getScanRecordById(ctx, id)

    /** 按 id 列表批量查询（带 appId 过滤和软删）。 */
    fun findByIds(ctx: RequestContext, ids: List<String>): List<ScanRecordEntity> =
        facade.findByIds(ctx, ids)

    /** 按游标分页查询扫描记录。 */
    fun findByCursor(
        ctx: RequestContext,
        cursor: String? = null,
        limit: Int? = null,
        collected: Boolean? = null,
    ) = facade.findByCursor(ctx, cursor, limit, collected)
}
