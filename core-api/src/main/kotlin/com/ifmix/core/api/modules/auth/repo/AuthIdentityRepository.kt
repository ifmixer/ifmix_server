package com.ifmix.core.api.modules.auth.repo

import com.ifmix.core.api.entity.auth.AuthIdentity
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.UuidV7
import com.ifmix.core.api.infra.repo.AppCrudRepoTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class AuthIdentityRepository {
    companion object { private val tpl = AppCrudRepoTemplate(AuthIdentity::class) }

    /** 新建账号（默认无密码）。返回新账号 id。 */
    fun createAccount(mc: ModuleCtx, appId: UUID, password: String? = null): UUID {
        val now = Instant.now()
        val id = UuidV7.generate()
        tpl.save(mc, AuthIdentity {
            this.id = id
            this.appId = appId
            this.password = password
            this.createdAt = now
            this.updatedAt = now
        })
        return id
    }

    fun findById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.findById(mc, appId, id)
    fun save(mc: ModuleCtx, entity: AuthIdentity) = tpl.save(mc, entity)
}
