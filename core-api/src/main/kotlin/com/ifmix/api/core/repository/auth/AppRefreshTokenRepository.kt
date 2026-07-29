package com.ifmix.api.core.repository.auth

import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import com.ifmix.api.core.entity.auth.AppRefreshToken
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** Refresh token repository with custom queries */
@Component
class AppRefreshTokenRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<AppRefreshToken>(sql, AppRefreshToken::class) {

    /**
     * Find a valid refresh token by (appId, tokenHash).
     * Valid = not revoked and not expired.
     */
    fun findValidByHash(appId: UUID, tokenHash: String): AppRefreshToken? {
        val now = Instant.now()
        return findAll().firstOrNull { rt ->
            rt.appId == appId
                && rt.tokenHash == tokenHash
                && rt.revokedAt == null
                && (rt.expiresAt == null || rt.expiresAt!!.isAfter(now))
        }
    }

    /**
     * Revoke a refresh token: set revokedAt and optionally replacedBy.
     */
    fun revoke(id: UUID, replacedBy: UUID? = null) {
        val existing = findById(id) ?: return
        val updated = AppRefreshToken {
            this.id = id
            this.appId = existing.appId
            appUser { this.id = existing.appUser.id }
            tokenHash = existing.tokenHash
            loginInstallId = existing.loginInstallId
            expiresAt = existing.expiresAt
            revokedAt = Instant.now()
            this.replacedBy = replacedBy
            createdAt = existing.createdAt
            updatedAt = Instant.now()
        }
        save(updated)
    }
}
