package com.ifmix.core.api.modules.ai.repo

import com.ifmix.core.api.entity.ai.AiApiKey
import com.ifmix.core.api.entity.ai.enabled
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.CrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AiApiKeyRepository {
    companion object { private val tpl = CrudRepoTemplate(AiApiKey::class, UUID::class) }

    fun findAllEnabled(mc: ModuleCtx): List<AiApiKey> =
        mc.sql.createQuery(AiApiKey::class) {
            where(table.enabled eq true)
            select(table)
        }.execute()

    fun save(mc: ModuleCtx, entity: AiApiKey) = tpl.save(mc, entity)
    fun findById(mc: ModuleCtx, id: UUID) = tpl.findById(mc, id)
    fun deleteById(mc: ModuleCtx, id: UUID): Boolean = tpl.deleteById(mc, id)
    fun exists(mc: ModuleCtx, id: UUID): Boolean = tpl.exists(mc, id)
}
