package com.ifmix.api.core.common.jimmer.repository.auth

import com.ifmix.api.core.common.jimmer.base.BaseCrudRepository
import com.ifmix.api.core.common.jimmer.entity.auth.AuthProviderIdentity
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component

/** Provider identity repository (basic CRUD only) */
@Component
class AuthProviderIdentityRepository(
    sql: KSqlClient,
) : BaseCrudRepository<AuthProviderIdentity>(sql, AuthProviderIdentity::class)
