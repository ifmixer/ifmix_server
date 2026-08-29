package com.ifmix.api.core.modules.ai.repo

import com.ifmix.api.core.entity.ai.ScanDeepResearch
import com.ifmix.api.core.entity.ai.appId
import com.ifmix.api.core.entity.ai.scanRecordId
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.AppCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ScanDeepResearchRepository {
    companion object {
        private val tpl = AppCrudRepoTemplate(ScanDeepResearch::class)
    }

    /**
     * 按 scanRecordId upsert：存在则更新 premiumResult，不存在则创建。
     * 依赖 ScanDeepResearch.scanRecordId 的 @Key（等价 DB 唯一索引）。
     */
    fun upsert(mc: ModuleCtx, entity: ScanDeepResearch): Boolean = tpl.save(mc, entity)

    fun findByScanRecordId(mc: ModuleCtx, appId: UUID, scanRecordId: UUID): ScanDeepResearch? =
        mc.sql.createQuery(ScanDeepResearch::class) {
            where(table.appId eq appId)
            where(table.scanRecordId eq scanRecordId)
            select(table)
        }.limit(1).execute().firstOrNull()

    /** 批量按 scanRecordId 查询（DataLoader 用）。 */
    fun findByScanRecordIds(mc: ModuleCtx, appId: UUID, scanRecordIds: Collection<UUID>): List<ScanDeepResearch> {
        if (scanRecordIds.isEmpty()) return emptyList()
        return mc.sql.createQuery(ScanDeepResearch::class) {
            where(table.appId eq appId)
            where(table.scanRecordId valueIn scanRecordIds)
            select(table)
        }.execute()
    }
}
