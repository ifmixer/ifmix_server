package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.AuthTenant
import com.ifmix.api.core.infra.repo.BaseCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Repository

@Repository
class AuthTenantRepository(sql: KSqlClient) : BaseCrudRepository<AuthTenant>(sql, AuthTenant::class)
