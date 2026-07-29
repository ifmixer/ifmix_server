package com.ifmix.api.core.common.jimmer.repository.auth

import com.ifmix.api.core.common.jimmer.base.BaseCrudRepository
import com.ifmix.api.core.common.jimmer.entity.auth.AuthIdentity
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component

/** AuthIdentity repository */
@Component
class AuthIdentityRepository(
    sql: KSqlClient,
) : BaseCrudRepository<AuthIdentity>(sql, AuthIdentity::class)
