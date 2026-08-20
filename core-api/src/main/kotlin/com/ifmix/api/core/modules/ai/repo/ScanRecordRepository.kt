package com.ifmix.api.core.modules.ai.repo

import com.ifmix.api.core.entity.ai.ScanRecord
import com.ifmix.api.core.entity.ai.ScanRecordProps
import com.ifmix.api.core.entity.ai.appId
import com.ifmix.api.core.entity.ai.collected
import com.ifmix.api.core.entity.ai.id
import com.ifmix.api.core.entity.ai.userDisplayName
import com.ifmix.api.core.entity.ai.userNotes
import com.ifmix.api.core.generated.types.ScanUnsetField
import com.ifmix.api.core.generated.types.UpdateScanInput
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.CrudRepoTemplate
import com.ifmix.api.core.infra.repo.FilterGroupResolver
import org.babyfish.jimmer.sql.kt.ast.expression.desc
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.lt
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ScanRecordRepository {
    companion object {
        private val tpl = CrudRepoTemplate(ScanRecord::class, appId = "appId")
        val FILTERABLE = listOf(
            ScanRecordProps.STATUS,
            ScanRecordProps.COLLECTED,
            ScanRecordProps.LANG,
            ScanRecordProps.CREATED_AT,
        )
    }

    fun findByCursor(mc: ModuleCtx, appId: UUID, collected: Boolean?, cursor: UUID?, limit: Int): List<ScanRecord> {
        return mc.sql.createQuery(ScanRecord::class) {
            where(table.appId eq appId)
            collected?.let { where(table.collected eq it) }
            cursor?.let { where(table.id lt it) }
            orderBy(table.id.desc())
            select(table)
        }.limit(limit).execute()
    }

    fun findByFilter(mc: ModuleCtx, appId: UUID, filter: com.ifmix.api.core.generated.types.FilterGroup?, cursor: UUID?, limit: Int): List<ScanRecord> {
        return mc.sql.createQuery(ScanRecord::class) {
            where(table.appId eq appId)
            FilterGroupResolver.apply(this, filter, FILTERABLE)
            cursor?.let { where(table.id lt it) }
            orderBy(table.id.desc())
            select(table)
        }.limit(limit).execute()
    }

    fun partialUpdate(mc: ModuleCtx, appId: UUID, id: UUID, req: UpdateScanInput): Int {
        val unset = req.unset?.toSet() ?: emptySet()
        return mc.sql.createUpdate(ScanRecord::class) {
            where(table.appId eq appId)
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
        }.execute()
    }

    fun save(mc: ModuleCtx, entity: ScanRecord) = tpl.save(mc, entity)
    fun findById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.findById(mc, appId, id)
    fun findByIds(mc: ModuleCtx, appId: UUID, ids: Collection<UUID>): List<ScanRecord> = tpl.findByIds(mc, appId, ids)
    fun deleteById(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.deleteById(mc, appId, id)
    fun exists(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.exists(mc, appId, id)
}
