package com.ifmix.api.core.modules.ai.repo

import com.ifmix.api.core.entity.ai.ScanCollection
import com.ifmix.api.core.entity.ai.appId
import com.ifmix.api.core.entity.ai.id
import com.ifmix.api.core.entity.ai.installId
import com.ifmix.api.core.entity.ai.isDefault
import com.ifmix.api.core.entity.ai.userId
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.AppCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ScanCollectionRepository {
    companion object { private val tpl = AppCrudRepoTemplate(ScanCollection::class) }

    fun findDefault(mc: ModuleCtx, appId: UUID, installId: UUID?, userId: UUID?): ScanCollection? {
        if (userId != null) {
            return mc.sql.createQuery(ScanCollection::class) {
                where(table.get<UUID>("appId") eq appId)
                where(table.isDefault eq true)
                where(table.userId eq userId)
                select(table)
            }.limit(1).execute().firstOrNull()
        }
        if (installId != null) {
            return mc.sql.createQuery(ScanCollection::class) {
                where(table.get<UUID>("appId") eq appId)
                where(table.isDefault eq true)
                where(table.installId eq installId)
                select(table)
            }.limit(1).execute().firstOrNull()
        }
        return null
    }

    fun save(mc: ModuleCtx, entity: ScanCollection) = tpl.save(mc, entity)
    fun findById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.findById(mc, appId, id)
    fun deleteById(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.deleteById(mc, appId, id)
    fun exists(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.exists(mc, appId, id)
}
