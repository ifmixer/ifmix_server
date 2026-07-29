package com.ifmix.api.core.repository.auth

import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import com.ifmix.api.core.entity.auth.AppRefreshToken
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component

/** Refresh token repository (basic CRUD only) */
@Component
class AppRefreshTokenRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<AppRefreshToken>(sql, AppRefreshToken::class)
