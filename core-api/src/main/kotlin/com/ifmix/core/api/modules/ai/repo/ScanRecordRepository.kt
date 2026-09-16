package com.ifmix.core.api.modules.ai.repo

import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.entity.ai.ScanRecord
import com.ifmix.core.api.entity.ai.ImageRef
import com.ifmix.core.api.entity.ai.ScanRecordProps
import com.ifmix.core.api.entity.ai.projectId
import com.ifmix.core.api.entity.ai.basicResult
import com.ifmix.core.api.entity.ai.collected
import com.ifmix.core.api.entity.ai.fetchBy
import com.ifmix.core.api.entity.ai.hasDeepSearch
import com.ifmix.core.api.entity.ai.id
import com.ifmix.core.api.entity.ai.images
import com.ifmix.core.api.entity.ai.isPublic
import com.ifmix.core.api.entity.ai.promptVersion
import com.ifmix.core.api.entity.ai.userDisplayName
import com.ifmix.core.api.entity.ai.userNotes
import com.ifmix.core.api.entity.ai.customerId
import com.ifmix.core.api.generated.types.CommonFindOptions
import com.ifmix.core.api.generated.types.ScanUnsetField
import com.ifmix.core.api.generated.types.UpdateScanInput
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.babyfish.jimmer.sql.ast.mutation.DeleteMode
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn
import org.springframework.stereotype.Repository
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

    fun partialUpdate(mc: ModuleCtx, projectId: String, id: UUID, req: UpdateScanInput): Int {
        val unset = req.unset?.toSet() ?: emptySet()
        return mc.sql.createUpdate(ScanRecord::class) {
            where(table.projectId eq projectId)
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

    /** DeepResearch 前置：整体替换 images（AI 失败也已提交）。 */
    fun updateImages(
        mc: ModuleCtx,
        projectId: String,
        id: UUID,
        images: List<ImageRef>,
    ): Int = mc.sql.createUpdate(ScanRecord::class) {
        where(table.projectId eq projectId)
        where(table.id eq id)
        set(table.images, images)
    }.execute()

    /** DeepResearch 后置：回写 basicResult + hasDeepSearch + promptVersion。 */
    fun updateResultAfterDeepResearch(
        mc: ModuleCtx,
        projectId: String,
        id: UUID,
        basicResult: Map<String, Any?>?,
        promptVersion: String,
    ): Int = mc.sql.createUpdate(ScanRecord::class) {
        where(table.projectId eq projectId)
        where(table.id eq id)
        set(table.basicResult, basicResult)
        set(table.hasDeepSearch, true)
        set(table.promptVersion, promptVersion)
    }.execute()
    fun findByIds(mc: ModuleCtx, projectId: String, ids: Collection<UUID>): List<ScanRecord> = tpl.findByIds(mc, projectId, ids)

    /** 列表视图批量查询：不加载 basicResult */
    fun findByIdsListView(mc: ModuleCtx, projectId: String, ids: Collection<UUID>): List<ScanRecord> {
        if (ids.isEmpty()) return emptyList()
        return mc.sql.createQuery(ScanRecord::class) {
            where(table.projectId eq projectId)
            where(table.id valueIn ids)
            select(table.fetchBy {
                allScalarFields()
                basicResult(false)
            })
        }.execute()
    }

    fun deleteById(mc: ModuleCtx, projectId: String, id: UUID): Boolean = tpl.deleteById(mc, projectId, id)
    fun exists(mc: ModuleCtx, projectId: String, id: UUID): Boolean = tpl.exists(mc, projectId, id)

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
