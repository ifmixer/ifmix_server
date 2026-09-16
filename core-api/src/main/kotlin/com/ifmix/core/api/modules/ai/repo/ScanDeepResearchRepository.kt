package com.ifmix.core.api.modules.ai.repo

import com.ifmix.core.api.entity.ai.ScanDeepResearch
import com.ifmix.core.api.entity.ai.projectId
import com.ifmix.core.api.entity.ai.scanRecordId
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ScanDeepResearchRepository {
    companion object {
        private val tpl = ProjectCrudRepoTemplate(ScanDeepResearch::class, UUID::class)
    }

    /**
     * 按 scanRecordId upsert：存在则更新 premiumResult，不存在则创建。
     * 依赖 ScanDeepResearch.scanRecordId 的 @Key（等价 DB 唯一索引）。
     */
    fun upsert(mc: ModuleCtx, entity: ScanDeepResearch): Boolean = tpl.save(mc, entity)

    fun findByScanRecordId(mc: ModuleCtx, projectId: String, scanRecordId: UUID): ScanDeepResearch? =
        mc.sql.createQuery(ScanDeepResearch::class) {
            where(table.projectId eq projectId)
            where(table.scanRecordId eq scanRecordId)
            select(table)
        }.limit(1).execute().firstOrNull()

    /** 批量按 scanRecordId 查询（DataLoader 用）。 */
    fun findByScanRecordIds(mc: ModuleCtx, projectId: String, scanRecordIds: Collection<UUID>): List<ScanDeepResearch> {
        if (scanRecordIds.isEmpty()) return emptyList()
        return mc.sql.createQuery(ScanDeepResearch::class) {
            where(table.projectId eq projectId)
            where(table.scanRecordId valueIn scanRecordIds)
            select(table)
        }.execute()
    }
}
