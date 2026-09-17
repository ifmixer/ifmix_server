package com.ifmix.core.api.modules.ai.repo

import com.ifmix.core.api.entity.ai.AgnesKey
import com.ifmix.core.api.entity.ai.enabled
import com.ifmix.core.api.entity.ai.id
import com.ifmix.core.api.entity.ai.unavailableUntil
import com.ifmix.core.api.entity.ai.updatedAt
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.CrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.isNull
import org.babyfish.jimmer.sql.kt.ast.expression.lt
import org.babyfish.jimmer.sql.kt.ast.expression.or
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class AgnesKeyRepository {
    companion object { private val tpl = CrudRepoTemplate(AgnesKey::class, UUID::class) }

    fun findAllEnabled(mc: ModuleCtx): List<AgnesKey> =
        mc.sql.createQuery(AgnesKey::class) {
            where(table.enabled eq true)
            select(table)
        }.execute()

    fun findAvailable(mc: ModuleCtx): List<AgnesKey> {
        val now = Instant.now()
        return mc.sql.createQuery(AgnesKey::class) {
            where(table.enabled eq true)
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
    fun findById(mc: ModuleCtx, id: UUID) = tpl.findById(mc, id)
    fun deleteById(mc: ModuleCtx, id: UUID): Boolean = tpl.deleteById(mc, id)
    fun exists(mc: ModuleCtx, id: UUID): Boolean = tpl.exists(mc, id)
}
