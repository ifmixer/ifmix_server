package com.ifmix.api.core.common.jimmer.repository.auth

import com.ifmix.api.core.common.jimmer.base.BaseAppCrudRepository
import com.ifmix.api.core.common.jimmer.entity.auth.AppRefreshToken
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component

/** Refresh token repository (basic CRUD only) */
@Component
class AppRefreshTokenRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<AppRefreshToken>(sql, AppRefreshToken::class)
