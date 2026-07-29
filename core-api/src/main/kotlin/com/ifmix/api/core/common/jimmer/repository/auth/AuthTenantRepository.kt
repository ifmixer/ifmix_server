package com.ifmix.api.core.common.jimmer.repository.auth

import com.ifmix.api.core.common.jimmer.base.BaseCrudRepository
import com.ifmix.api.core.common.jimmer.entity.auth.AuthTenant
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component

/** AuthTenant repository */
@Component
class AuthTenantRepository(
    sql: KSqlClient,
) : BaseCrudRepository<AuthTenant>(sql, AuthTenant::class)
