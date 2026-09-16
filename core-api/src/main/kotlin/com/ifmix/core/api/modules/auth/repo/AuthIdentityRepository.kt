package com.ifmix.core.api.modules.auth.repo

import com.ifmix.core.api.entity.auth.AuthIdentity
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.UuidV7
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class AuthIdentityRepository {
    companion object { private val tpl = ProjectCrudRepoTemplate(AuthIdentity::class, UUID::class) }

    /** 新建账号（默认无密码）。返回新账号 id。 */
    fun createAccount(mc: ModuleCtx, projectId: String, password: String? = null): UUID {
        val now = Instant.now()
        val id = UuidV7.generate()
        tpl.save(mc, AuthIdentity {
            this.id = id
            this.projectId = projectId
            this.password = password
            this.createdAt = now
            this.updatedAt = now
        })
        return id
    }

    fun findById(mc: ModuleCtx, projectId: String, id: UUID) = tpl.findById(mc, projectId, id)
    fun save(mc: ModuleCtx, entity: AuthIdentity) = tpl.save(mc, entity)
}
