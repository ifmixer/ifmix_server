package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.AppUser
import com.ifmix.api.core.entity.auth.appId
import com.ifmix.api.core.entity.auth.authIdentityId
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.repo.AppCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class AppUserRepository {
    companion object { private val tpl = AppCrudRepoTemplate(AppUser::class) }

    fun findByAppAndIdentity(mc: ModuleCtx, appId: UUID, authIdentityId: UUID): AppUser? {
        return mc.sql.createQuery(AppUser::class) {
            where(table.get<UUID>("appId") eq appId)
            where(table.authIdentityId eq authIdentityId)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun ensure(mc: ModuleCtx, appId: UUID, authIdentityId: UUID): UUID {
        val existing = findByAppAndIdentity(mc, appId, authIdentityId)
        if (existing != null) return existing.id
        val now = Instant.now()
        val id = UuidV7.generate()
        val entity = AppUser {
            this.id = id
            this.appId = appId
            this.authIdentityId = authIdentityId
            this.createdAt = now
            this.updatedAt = now
        }
        mc.sql.entities.save(entity)
        return id
    }

    fun save(mc: ModuleCtx, entity: AppUser) = tpl.save(mc, entity)
    fun findById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.findById(mc, appId, id)
    fun deleteById(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.deleteById(mc, appId, id)
    fun exists(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.exists(mc, appId, id)
}
