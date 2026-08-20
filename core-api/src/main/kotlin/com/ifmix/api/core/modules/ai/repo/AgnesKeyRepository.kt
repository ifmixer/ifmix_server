package com.ifmix.api.core.modules.ai.repo

import com.ifmix.api.core.entity.ai.AgnesKey
import com.ifmix.api.core.entity.ai.appId
import com.ifmix.api.core.entity.ai.id
import com.ifmix.api.core.entity.ai.unavailableUntil
import com.ifmix.api.core.entity.ai.updatedAt
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.CrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.isNull
import org.babyfish.jimmer.sql.kt.ast.expression.lt
import org.babyfish.jimmer.sql.kt.ast.expression.or
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class AgnesKeyRepository {
    companion object { private val tpl = CrudRepoTemplate(AgnesKey::class, appId = "appId") }

    fun findAllEnabled(mc: ModuleCtx): List<AgnesKey> =
        mc.sql.createQuery(AgnesKey::class) {
            select(table)
        }.execute()

    fun findAvailable(mc: ModuleCtx, appId: UUID): List<AgnesKey> {
        val now = Instant.now()
        return mc.sql.createQuery(AgnesKey::class) {
            where(table.get<UUID>("appId") eq appId)
            where(
                or(
                    table.unavailableUntil.isNull(),
                    table.unavailableUntil lt now
                )
            )
            select(table)
        }.execute()
    }

    fun markUnavailable(mc: ModuleCtx, keyId: UUID, until: Instant): Int {
        return mc.sql.createUpdate(AgnesKey::class) {
            where(table.id eq keyId)
            set(table.unavailableUntil, until)
            set(table.updatedAt, Instant.now())
        }.execute()
    }

    fun save(mc: ModuleCtx, entity: AgnesKey) = tpl.save(mc, entity)
    fun findById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.findById(mc, appId, id)
    fun deleteById(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.deleteById(mc, appId, id)
    fun exists(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.exists(mc, appId, id)
}
