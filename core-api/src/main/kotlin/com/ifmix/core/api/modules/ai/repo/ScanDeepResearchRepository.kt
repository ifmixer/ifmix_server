package com.ifmix.core.api.modules.ai.repo

import com.ifmix.core.api.entity.ai.DeepResearchStatuses
import com.ifmix.core.api.entity.ai.ScanDeepResearch
import com.ifmix.core.api.entity.ai.basicResult
import com.ifmix.core.api.entity.ai.errorCode
import com.ifmix.core.api.entity.ai.errorDetails
import com.ifmix.core.api.entity.ai.id
import com.ifmix.core.api.entity.ai.projectId
import com.ifmix.core.api.entity.ai.premiumResult
import com.ifmix.core.api.entity.ai.status
import com.ifmix.core.api.entity.ai.updatedAt
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class ScanDeepResearchRepository {
    companion object {
        private val tpl = ProjectCrudRepoTemplate(ScanDeepResearch::class, UUID::class)
    }

    fun insert(mc: ModuleCtx, entity: ScanDeepResearch) = tpl.save(mc, entity)

    fun findById(mc: ModuleCtx, projectId: String, id: UUID): ScanDeepResearch? =
        tpl.findById(mc, projectId, id)

    /** 批量按 id 查询（latestDeepResearch DataLoader 用；owner 由父 ScanRecord 保证）。 */
    fun findByIds(mc: ModuleCtx, projectId: String, ids: Collection<UUID>): List<ScanDeepResearch> {
        if (ids.isEmpty()) return emptyList()
        return mc.sql.createQuery(ScanDeepResearch::class) {
            where(table.projectId eq projectId)
            where(table.id valueIn ids)
            select(table)
        }.execute()
    }

    /**
     * 终态 CAS（设计 §3.4）：IN_PROGRESS → SUCCESS，同时写 premium_result + basic_result（JSONB 入 PG）。
     * basic_result 为本次成功的快照（统计分析用，每条历史记录各存自己那次）。
     * 返回 affected==1 表示当前路径是赢家；0 = 已被其它路径终结（查询惰性超时抢先等），
     * 调用方放弃后续动作（不重复扣配额、不重复回写）。
     */
    fun casSuccess(mc: ModuleCtx, id: UUID, premiumResult: Map<String, Any?>?, basicResult: Map<String, Any?>?): Int =
        mc.sql.createUpdate(ScanDeepResearch::class) {
            where(table.id eq id)
            where(table.status eq DeepResearchStatuses.IN_PROGRESS)
            set(table.status, DeepResearchStatuses.SUCCESS)
            set(table.premiumResult, premiumResult)
            set(table.basicResult, basicResult)
            set(table.updatedAt, Instant.now())
        }.execute()

    /**
     * 终态 CAS：IN_PROGRESS → FAILED，写稳定错误码与结构化详情（不含异常栈）。
     * 返回 0 = 已被其它路径终结，调用方放弃。
     */
    fun casFailed(mc: ModuleCtx, id: UUID, errorCode: String, errorDetails: Map<String, Any?>?): Int =
        mc.sql.createUpdate(ScanDeepResearch::class) {
            where(table.id eq id)
            where(table.status eq DeepResearchStatuses.IN_PROGRESS)
            set(table.status, DeepResearchStatuses.FAILED)
            set(table.errorCode, errorCode)
            set(table.errorDetails, errorDetails)
            set(table.updatedAt, Instant.now())
        }.execute()
}
