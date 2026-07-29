package com.ifmix.api.core.common.jimmer.repository.auth

import com.ifmix.api.core.common.auth.Hashing
import com.ifmix.api.core.common.jimmer.base.BaseCrudRepository
import com.ifmix.api.core.common.jimmer.entity.auth.AuthDeviceSecret
import org.babyfish.jimmer.Input
import org.babyfish.jimmer.sql.kt.*
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Device secret repository with custom issue(), findValid(), touch(), revoke() methods */
@Component
class AuthDeviceSecretRepository(
    sql: KSqlClient,
) : BaseCrudRepository<AuthDeviceSecret>(sql, AuthDeviceSecret::class) {
    private val idleTtl = Duration.ofDays(90)

    /** Issue new device secret. Returns pair (id, plain secret). */
    fun issue(tenantId: String, authIdentityId: String, loginInstallId?: String): Pair<String, String> {
        val tenantUUID = try { UUID.fromString(tenantId) } catch (e: Exception) { throw IllegalArgumentException("Invalid tenantId") }
        val identityId = try { UUID.fromString(authIdentityId) } catch (e: Exception) { throw IllegalArgumentException("Invalid authIdentityId") }

        val secret = Hashing.randomTokenBase64Url()
        val now = Instant.now()
        val secretHash = Hashing.sha256Base64Url(secret)
        val id = UUID.randomUUID().toString()

        val input: Input<AuthDeviceSecret> = sql.input(AuthDeviceSecret::class.java) {
            set("id", UUID(id))
            set("authTenantId", tenantUUID)
            set("authIdentityId", identityId)
            set("secretHash", secretHash)
            set("loginInstallId", loginInstallId)
            set("expiresAt", now.plus(idleTtl))
            set("lastUsedAt", now)
            set("createdAt", now)
            set("updatedAt", now)
        }
        insert(input)
        return id to secret
    }

    /** Find valid device secret by tenantId and plain secret. Returns null if not found or expired/revoked. */
    fun findValid(tenantId: String, secretPlain: String)? {
        val tenantUUID = try { UUID.fromString(tenantId) } catch (e: Exception) { throw IllegalArgumentException("Invalid tenantId") }
        val secretHash = Hashing.sha256Base64Url(secretPlain)
        val now = Instant.now()

        val all = this.findAll()
        return all.firstOrNull { ds ->
            ds.authTenant.id == tenantUUID &&
            ds.secretHash == secretHash &&
            ds.revokedAt == null &&
            (ds.expiresAt?.isAfter(now) == true)
        }
    }

    /** Touch: update lastUsedAt and extend expiresAt for the given device secret ID. */
    fun touch(id: String) {
        try {
            val uuidId = UUID.fromString(id)
            val now = Instant.now()
            // Since entities are immutable, we need to use Input to update
            val existing = findById(uuidId) ?: return
            val input: Input<AuthDeviceSecret> = sql.input(AuthDeviceSecret::class.java) {
                set("id", uuidId)
                set("lastUsedAt", now)
                set("expiresAt", now.plus(idleTtl))
                set("updatedAt", now)
                // Copy other fields from existing to avoid clearing them
                set("authTenantId", existing.authTenant.id)
                set("authIdentityId", existing.authIdentity.id)
                set("secretHash", existing.secretHash)
                set("loginInstallId", existing.loginInstallId)
            }
            save(input)
        } catch (e: Exception) { /* ignore */ }
    }

    /** Revoke the device secret by ID. */
    fun revoke(id: String) {
        try {
            val uuidId = UUID.fromString(id)
            val now = Instant.now()
            val existing = findById(uuidId) ?: return
            val input: Input<AuthDeviceSecret> = sql.input(AuthDeviceSecret::class.java) {
                set("id", uuidId)
                set("revokedAt", now)
                set("updatedAt", now)
                set("authTenantId", existing.authTenant.id)
                set("authIdentityId", existing.authIdentity.id)
                set("secretHash", existing.secretHash)
                set("loginInstallId", existing.loginInstallId)
            }
            save(input)
        } catch (e: Exception) { /* ignore */ }
    }
}