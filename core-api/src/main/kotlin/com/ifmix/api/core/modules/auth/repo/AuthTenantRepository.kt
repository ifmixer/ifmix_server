package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.AuthTenant
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.CrudRepoTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AuthTenantRepository {
    companion object { private val tpl = CrudRepoTemplate(AuthTenant::class) }
}
