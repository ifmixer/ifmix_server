package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.AppRefreshToken
import com.ifmix.api.core.entity.auth.appId
import com.ifmix.api.core.entity.auth.expiresAt
import com.ifmix.api.core.entity.auth.id
import com.ifmix.api.core.entity.auth.replacedBy
import com.ifmix.api.core.entity.auth.revokedAt
import com.ifmix.api.core.entity.auth.tokenHash
import com.ifmix.api.core.entity.auth.updatedAt
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/** Refresh token repository with custom queries */
@Repository
class AppRefreshTokenRepository(sql: KSqlClient,) : BaseAppCrudRepository<AppRefreshToken>(sql, AppRefreshToken::class) {

    /**
     * Find a valid refresh token by (appId, tokenHash).
     * Valid = not revoked and not expired.
     */
    fun findValidByHash(ctx: RepoContext, appId: UUID, tokenHash: String): AppRefreshToken? {
        val now = Instant.now()
        return sql.createQuery(AppRefreshToken::class) {
            where(table.appId eq appId)
            where(table.tokenHash eq tokenHash)
            where(table.revokedAt.isNull())
            where(or(table.expiresAt.isNull(), table.expiresAt gt now))
            select(table)
        }.fetchOneOrNull()
    }

    /**
     * Revoke a refresh token: set revokedAt and optionally replacedBy.
     */
    fun revoke(ctx: RepoContext, id: UUID, replacedBy: UUID? = null) {
        sql.createUpdate(AppRefreshToken::class) {
            set(table.revokedAt, Instant.now())
            set(table.updatedAt, Instant.now())
            if (replacedBy != null) {
                set(table.replacedBy, replacedBy)
            }
            where(table.id eq id)
        }.execute()
    }
}
