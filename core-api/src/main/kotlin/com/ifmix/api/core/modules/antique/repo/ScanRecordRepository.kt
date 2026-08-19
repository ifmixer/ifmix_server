package com.ifmix.api.core.modules.antique.repo

import com.ifmix.api.core.common.db.CRUDOps
import com.ifmix.api.core.common.http.RepoCtx
import com.ifmix.api.core.common.http.RequestContext
import org.springframework.data.mongodb.core.MongoTemplate
import com.ifmix.api.core.modules.antique.entity.ScanRecordEntity
import com.ifmix.api.core.modules.antique.repo.ScanRecordRepository

/**
 * 古物扫描记录仓储。
 *
 * 持有 CRUDOps 实例获得基础 CRUD + 游标分页，无需额外实现。
 */
class ScanRecordRepository(
    mongo: MongoTemplate,
) {
    private val ops = CRUDOps<ScanRecordEntity>(mongo, ScanRecordEntity::class.java)

    /** 按 scanId 查找最新一条记录。 */
    fun findByScanId(ctx: RequestContext, scanId: String): ScanRecordEntity? {
        // 使用自定义查询：按 scanId + appId 过滤
        return null // stub — 后续按需实现
    }

    // ---- deprecated adapters for Phase 1 callers（Task 8 will migrate to RepoCtx）----

    @Deprecated("Use CRUDOps with RepoCtx instead. Migrate in Task 8.", level = DeprecationLevel.WARNING)
    fun findById(ctx: RequestContext, id: String): ScanRecordEntity? =
        ops.findById(RepoCtx(), ctx.appId, id)

    @Deprecated("Use CRUDOps with RepoCtx instead. Migrate in Task 8.", level = DeprecationLevel.WARNING)
    fun getById(ctx: RequestContext, id: String): ScanRecordEntity =
        ops.getById(RepoCtx(), ctx.appId, id)

    @Deprecated("Use CRUDOps with RepoCtx instead. Migrate in Task 8.", level = DeprecationLevel.WARNING)
    fun updateById(ctx: RequestContext, id: String, patch: Any): Boolean =
        ops.updateById(RepoCtx(), ctx.appId, id, patch)

    @Deprecated("Use CRUDOps with RepoCtx instead. Migrate in Task 8.", level = DeprecationLevel.WARNING)
    fun updateByIdWithUnset(ctx: RequestContext, id: String, patch: Any, unsetFields: List<String>? = null): Boolean =
        ops.updateByIdWithUnset(RepoCtx(), ctx.appId, id, patch, unsetFields)

    @Deprecated("Use CRUDOps with RepoCtx instead. Migrate in Task 8.", level = DeprecationLevel.WARNING)
    fun deleteById(ctx: RequestContext, id: String): Boolean =
        ops.deleteById(RepoCtx(), ctx.appId, id)

    @Deprecated("Use CRUDOps with RepoCtx instead. Migrate in Task 8.", level = DeprecationLevel.WARNING)
    fun findByIds(ctx: RequestContext, ids: List<String>): List<ScanRecordEntity> =
        ops.findByIds(RepoCtx(), ctx.appId, ids)

    @Deprecated("Use CRUDOps with RepoCtx instead. Migrate in Task 8.", level = DeprecationLevel.WARNING)
    fun updateByIds(ctx: RequestContext, patches: Map<String, Any>): Int =
        ops.updateByIds(RepoCtx(), ctx.appId, patches)

    @Deprecated("Use CRUDOps with RepoCtx instead. Migrate in Task 8.", level = DeprecationLevel.WARNING)
    fun deleteByIds(ctx: RequestContext, ids: List<String>): Int =
        ops.deleteByIds(RepoCtx(), ctx.appId, ids)

    @Deprecated("Use CRUDOps with RepoCtx instead. Migrate in Task 8.", level = DeprecationLevel.WARNING)
    fun findByCursor(ctx: RequestContext, input: com.ifmix.api.core.common.db.CursorQueryInput = com.ifmix.api.core.common.db.CursorQueryInput()): com.ifmix.api.core.common.db.Page<ScanRecordEntity> =
        ops.findByCursor(RepoCtx(), ctx.appId, input)
}
