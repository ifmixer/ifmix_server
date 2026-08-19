package com.ifmix.api.core.modules.antique

import com.ifmix.api.core.common.db.CRUDRepository
import com.ifmix.api.core.common.http.RequestContext
import org.springframework.data.mongodb.core.MongoTemplate

/**
 * 古物扫描记录仓储。
 *
 * 继承 CRUDRepository 获得基础 CRUD + 游标分页，无需额外实现。
 */
class ScanRecordRepository(
    mongo: MongoTemplate,
) : CRUDRepository<ScanRecordEntity>(mongo, ScanRecordEntity::class.java) {

    /** 按 scanId 查找最新一条记录。 */
    fun findByScanId(ctx: RequestContext, scanId: String): ScanRecordEntity? {
        // 使用自定义查询：按 scanId + appId 过滤
        return null // stub — 后续按需实现
    }
}
