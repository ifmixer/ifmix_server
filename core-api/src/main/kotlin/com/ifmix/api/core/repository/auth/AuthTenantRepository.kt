package com.ifmix.api.core.repository.auth

import com.ifmix.api.core.repository.base.BaseCrudRepository
import com.ifmix.api.core.entity.auth.AuthTenant
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Repository

/** AuthTenant repository */
@Repository
class AuthTenantRepository(sql: KSqlClient,) : BaseCrudRepository<AuthTenant>(sql, AuthTenant::class)
