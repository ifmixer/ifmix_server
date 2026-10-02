package com.ifmix.core.api.modules.ai.repo

import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.entity.ai.ScanDeepResearch
import com.ifmix.core.api.entity.ai.ScanRecord
import com.ifmix.core.api.entity.ai.ImageRef
import com.ifmix.core.api.entity.ai.ScanRecordProps
import com.ifmix.core.api.entity.ai.projectId
import com.ifmix.core.api.entity.ai.basicResult
import com.ifmix.core.api.entity.ai.collected
import com.ifmix.core.api.entity.ai.customerId
import com.ifmix.core.api.entity.ai.fetchBy
import com.ifmix.core.api.entity.ai.hasDeepSearch
import com.ifmix.core.api.entity.ai.id
import com.ifmix.core.api.entity.ai.images
import com.ifmix.core.api.entity.ai.isPublic
import com.ifmix.core.api.entity.ai.latestDeepResearchId
import com.ifmix.core.api.entity.ai.promptVersion
import com.ifmix.core.api.entity.ai.updatedAt
import com.ifmix.core.api.entity.ai.userDisplayName
import com.ifmix.core.api.entity.ai.userNotes
import com.ifmix.core.api.generated.types.CommonFindOptions
import com.ifmix.core.api.generated.types.ScanUnsetField
import com.ifmix.core.api.generated.types.UpdateScanInput
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.babyfish.jimmer.sql.ast.mutation.DeleteMode
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.isNull
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class ScanRecordRepository {
    companion object {
        private val tpl = ProjectCrudRepoTemplate(ScanRecord::class, UUID::class)
        val FILTERABLE = listOf(
            ScanRecordProps.STATUS,
            ScanRecordProps.COLLECTED,
            ScanRecordProps.CREATED_AT,
            ScanRecordProps.UPDATED_AT,
            ScanRecordProps.LOCALE,
            ScanRecordProps.COUNTRY,
            ScanRecordProps.CURRENCY,
        )
    }

    /** 列表视图：不加载 basicResult JSONB 大字段 */
    fun findMyScans(mc: ModuleCtx, projectId: String, customerId: UUID, findOptions: CommonFindOptions?): Page<ScanRecord> =
        tpl.findByOptions(mc, projectId, findOptions, FILTERABLE, fetchBy = { fetchBy {
            allScalarFields()
            basicResult(false)
        } }) {
            where(table.customerId eq customerId)
        }

    fun partialUpdate(mc: ModuleCtx, projectId: String, customerId: UUID, id: UUID, req: UpdateScanInput): Int {
        val unset = req.unset?.toSet() ?: emptySet()
        return mc.sql.createUpdate(ScanRecord::class) {
            where(table.projectId eq projectId)
            where(table.customerId eq customerId)
            where(table.id eq id)
            // unset 优先：如果字段同时出现在 set 和 unset，以 unset 为准
            if (ScanUnsetField.USER_DISPLAY_NAME in unset) {
                set(table.userDisplayName, null as String?)
            } else {
                req.set?.userDisplayName?.let { set(table.userDisplayName, it) }
            }
            if (ScanUnsetField.USER_NOTES in unset) {
                set(table.userNotes, null as String?)
            } else {
                req.set?.userNotes?.let { set(table.userNotes, it) }
            }
            if (ScanUnsetField.USER_DISPLAY_NAME !in unset && ScanUnsetField.USER_NOTES !in unset) {
                req.set?.collected?.let { set(table.collected, it) }
            }
            req.set?.isPublic?.let { set(table.isPublic, it) }
        }.execute()
    }

    fun save(mc: ModuleCtx, entity: ScanRecord) = tpl.save(mc, entity)
    fun findById(mc: ModuleCtx, projectId: String, id: UUID) = tpl.findById(mc, projectId, id)

    /** owner-scoped 单条查询：仅当 projectId + customerId + id 匹配时返回。跨 customer 返回 null。 */
    fun findByIdOwned(mc: ModuleCtx, projectId: String, customerId: UUID, id: UUID): ScanRecord? =
        mc.sql.createQuery(ScanRecord::class) {
            where(table.projectId eq projectId)
            where(table.customerId eq customerId)
            where(table.id eq id)
            select(table)
        }.limit(1).execute().firstOrNull()

    /** owner-scoped 存在性判断（不加载大字段）。 */
    fun existsOwned(mc: ModuleCtx, projectId: String, customerId: UUID, id: UUID): Boolean =
        mc.sql.createQuery(ScanRecord::class) {
            where(table.projectId eq projectId)
            where(table.customerId eq customerId)
            where(table.id eq id)
            select(table.id)
        }.limit(1).execute().isNotEmpty()

    /**
     * 批量部分更新（owner-scoped）：仅更新 projectId + customerId 名下、且 id 在列表内的记录。
     * 非本人拥有的 id 不会被更新，天然完成 owner 校验。返回实际更新行数。
     * ids 为空返回 0；set 中所有字段为 null 返回 0（无字段可更新）。
     */
    fun batchPartialUpdate(
        mc: ModuleCtx,
        projectId: String,
        customerId: UUID,
        ids: Collection<UUID>,
        collected: Boolean?,
        isPublic: Boolean?,
    ): Int {
        if (ids.isEmpty()) return 0
        if (collected == null && isPublic == null) return 0
        return mc.sql.createUpdate(ScanRecord::class) {
            where(table.projectId eq projectId)
            where(table.customerId eq customerId)
            where(table.id valueIn ids)
            collected?.let { set(table.collected, it) }
            isPublic?.let { set(table.isPublic, it) }
        }.execute()
    }

    /** 合并：把 fromCustomerId 名下扫描记录归属改到 toCustomerId。返回改写行数。 */
    fun reassignOwner(mc: ModuleCtx, projectId: String, fromCustomerId: UUID, toCustomerId: UUID): Int =
        mc.sql.createUpdate(ScanRecord::class) {
            where(table.projectId eq projectId)
            where(table.customerId eq fromCustomerId)
            set(table.customerId, toCustomerId)
        }.execute()

    /** DeepResearch 前置：整体替换 images（AI 失败也已提交）。owner-scoped。 */
    fun updateImages(
        mc: ModuleCtx,
        projectId: String,
        customerId: UUID,
        id: UUID,
        images: List<ImageRef>,
    ): Int = mc.sql.createUpdate(ScanRecord::class) {
        where(table.projectId eq projectId)
        where(table.customerId eq customerId)
        where(table.id eq id)
        set(table.images, images)
    }.execute()

    /**
     * DeepResearch 成功回写（事务内调用，设计 §3.4）：basicResult/hasDeepSearch/promptVersion 与
     * latestDeepResearchId 必须**同语句**更新——保证 basic_result 与 pointer 指向同一次成功（不会一个新一个旧）。
     * 「旧任务晚完成不覆盖」用 (created_at, id) 比较 + 条件 UPDATE 复核（乐观锁）：
     * 先读当前指针指向任务的 (created_at,id)，新任务不比它新直接返回 false；
     * 更新语句再以「latestDeepResearchId 仍等于读取值」为条件——并发赢家先行改指针时 affected=0 → false（不扣配额）。
     */
    fun updateAiFieldsAndPointerIfNewer(
        mc: ModuleCtx,
        projectId: String,
        customerId: UUID,
        scanRecordId: UUID,
        deepResearchId: UUID,
        deepResearchCreatedAt: Instant,
        basicResult: Map<String, Any?>?,
        promptVersion: String,
    ): Boolean {
        val currentPointer = mc.sql.createQuery(ScanRecord::class) {
            where(table.projectId eq projectId)
            where(table.id eq scanRecordId)
            select(table.latestDeepResearchId)
        }.limit(1).execute().firstOrNull()

        if (currentPointer != null) {
            val current = mc.sql.createQuery(ScanDeepResearch::class) {
                where(table.projectId eq projectId)
                where(table.id eq currentPointer)
                select(table)
            }.limit(1).execute().firstOrNull()
            // 当前指针指向的记录不存在（理论不会发生）时视作可覆盖
            if (current != null && !isNewer(deepResearchCreatedAt, deepResearchId, current.createdAt, current.id)) {
                return false
            }
        }

        val affected = mc.sql.createUpdate(ScanRecord::class) {
            where(table.projectId eq projectId)
            where(table.customerId eq customerId)
            where(table.id eq scanRecordId)
            if (currentPointer == null) where(table.latestDeepResearchId.isNull())
            else where(table.latestDeepResearchId eq currentPointer)
            set(table.basicResult, basicResult)
            set(table.hasDeepSearch, true)
            set(table.promptVersion, promptVersion)
            set(table.latestDeepResearchId, deepResearchId)
            set(table.updatedAt, Instant.now())
        }.execute()
        return affected > 0
    }

    /** (created_at, id) 二元组比较：a 是否严格晚于 b（同时间用 id 兜底，设计 §3.4）。 */
    internal fun isNewer(aCreatedAt: Instant, aId: UUID, bCreatedAt: Instant, bId: UUID): Boolean =
        aCreatedAt > bCreatedAt || (aCreatedAt == bCreatedAt && aId > bId)
    fun findByIds(mc: ModuleCtx, projectId: String, ids: Collection<UUID>): List<ScanRecord> = tpl.findByIds(mc, projectId, ids)

    /** 列表视图批量查询（owner-scoped）：不加载 basicResult，仅返回该 customer 名下的记录。 */
    fun findByIdsListView(mc: ModuleCtx, projectId: String, customerId: UUID, ids: Collection<UUID>): List<ScanRecord> {
        if (ids.isEmpty()) return emptyList()
        return mc.sql.createQuery(ScanRecord::class) {
            where(table.projectId eq projectId)
            where(table.customerId eq customerId)
            where(table.id valueIn ids)
            select(table.fetchBy {
                allScalarFields()
                basicResult(false)
            })
        }.execute()
    }

    /** owner-scoped 批量 id 过滤：仅返回属于该 customer 的 id（批量读兜底，如 DeepResearch loader）。 */
    fun findOwnedIdsByIds(mc: ModuleCtx, projectId: String, customerId: UUID, ids: Collection<UUID>): List<UUID> {
        if (ids.isEmpty()) return emptyList()
        return mc.sql.createQuery(ScanRecord::class) {
            where(table.projectId eq projectId)
            where(table.customerId eq customerId)
            where(table.id valueIn ids)
            select(table.id)
        }.execute()
    }

    fun deleteById(mc: ModuleCtx, projectId: String, id: UUID): Boolean = tpl.deleteById(mc, projectId, id)
    fun exists(mc: ModuleCtx, projectId: String, id: UUID): Boolean = tpl.exists(mc, projectId, id)

    /** owner-scoped 删除：仅当 projectId + customerId + id 匹配才删。跨 customer 返回 false。 */
    fun deleteByIdOwned(mc: ModuleCtx, projectId: String, customerId: UUID, id: UUID): Boolean {
        val count = mc.sql.createDelete(ScanRecord::class) {
            where(table.projectId eq projectId)
            where(table.customerId eq customerId)
            where(table.id eq id)
        }.execute()
        return count > 0
    }

    /** 阶段 6：物理删除某批 customer 名下扫描记录（含软删列，显式 PHYSICAL 硬删避免孤儿行）。 */
    fun physicalDeleteByCustomers(mc: ModuleCtx, projectId: String, customerIds: Collection<UUID>): Int {
        if (customerIds.isEmpty()) return 0
        return mc.sql.createDelete(ScanRecord::class) {
            setMode(DeleteMode.PHYSICAL)
            where(table.projectId eq projectId)
            where(table.customerId valueIn customerIds)
        }.execute()
    }
}
