package com.ifmix.api.core.repository.auth

import com.ifmix.api.core.repository.base.BaseCrudRepository
import com.ifmix.api.core.entity.auth.AuthDeviceSecret
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** Device secret repository with custom queries */
@Component
class AuthDeviceSecretRepository(
    sql: KSqlClient,
) : BaseCrudRepository<AuthDeviceSecret>(sql, AuthDeviceSecret::class) {

    /**
     * Find a valid (not expired, not revoked) device secret by its hash.
     */
    fun findValidByHash(secretHash: String): AuthDeviceSecret? {
        val now = Instant.now()
        return findAll().firstOrNull { ds ->
            ds.secretHash == secretHash
                && ds.revokedAt == null
                && (ds.expiresAt == null || ds.expiresAt!!.isAfter(now))
        }
    }

    /**
     * Touch: update lastUsedAt to now.
     */
    fun touch(id: UUID) {
        val existing = findById(id) ?: return
        val updated = AuthDeviceSecret {
            this.id = id
            authTenant { this.id = existing.authTenant.id }
            authIdentity { this.id = existing.authIdentity.id }
            secretHash = existing.secretHash
            loginInstallId = existing.loginInstallId
            expiresAt = existing.expiresAt
            revokedAt = existing.revokedAt
            lastUsedAt = Instant.now()
            createdAt = existing.createdAt
            updatedAt = Instant.now()
        }
        save(updated)
    }

    /**
     * Revoke a device secret by setting revokedAt.
     */
    fun revoke(id: UUID) {
        val existing = findById(id) ?: return
        val updated = AuthDeviceSecret {
            this.id = id
            authTenant { this.id = existing.authTenant.id }
            authIdentity { this.id = existing.authIdentity.id }
            secretHash = existing.secretHash
            loginInstallId = existing.loginInstallId
            expiresAt = existing.expiresAt
            revokedAt = Instant.now()
            lastUsedAt = existing.lastUsedAt
            createdAt = existing.createdAt
            updatedAt = Instant.now()
        }
        save(updated)
    }
}
