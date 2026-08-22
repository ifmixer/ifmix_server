package com.ifmix.api.core.modules.user.repo

import com.ifmix.api.core.entity.user.AppUser
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.repo.AppCrudRepoTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class AppUserRepository {
    companion object { private val tpl = AppCrudRepoTemplate(AppUser::class) }

    fun createAppUser(mc: ModuleCtx, appId: UUID): UUID {
        val now = Instant.now()
        val id = UuidV7.generate()
        val entity = AppUser {
            this.id = id
            this.appId = appId
            this.metadata = null
            this.createdAt = now
            this.updatedAt = now
        }
        mc.sql.entities.save(entity)
        return id
    }

    fun save(mc: ModuleCtx, entity: AppUser) = tpl.save(mc, entity)
    fun findById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.findById(mc, appId, id)
    fun findByIds(mc: ModuleCtx, appId: UUID, ids: Collection<UUID>) = tpl.findByIds(mc, appId, ids)
    fun deleteById(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.deleteById(mc, appId, id)
    fun exists(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.exists(mc, appId, id)
}
