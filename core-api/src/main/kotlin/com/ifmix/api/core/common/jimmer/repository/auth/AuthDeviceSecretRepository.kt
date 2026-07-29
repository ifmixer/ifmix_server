package com.ifmix.api.core.common.jimmer.repository.auth

import com.ifmix.api.core.common.jimmer.base.BaseCrudRepository
import com.ifmix.api.core.common.jimmer.entity.auth.AuthDeviceSecret
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component

/** Device secret repository (basic CRUD only) */
@Component
class AuthDeviceSecretRepository(
    sql: KSqlClient,
) : BaseCrudRepository<AuthDeviceSecret>(sql, AuthDeviceSecret::class)
