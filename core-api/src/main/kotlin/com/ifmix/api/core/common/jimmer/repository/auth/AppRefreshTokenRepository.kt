package com.ifmix.api.core.common.jimmer.repository.auth

import com.ifmix.api.core.common.auth.Hashing
import com.ifmix.api.core.common.jimmer.base.BaseAppCrudRepository
import com.ifmix.api.core.common.jimmer.entity.auth.AppRefreshToken
import org.babyfish.jimmer.Input
import org.babyfish.jimmer.sql.kt.*
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Refresh token repository with custom issue(), findByHash(), tryRotate(), revokeByAppUser(), revokeByDeviceSecret() methods */
@Component
class AppRefreshTokenRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<AppRefreshToken>(sql, AppRefreshToken::class) {
    private val ttl = Duration.ofDays(30)

    data class RefreshIssued(val id: String, val token: String, val expiresAt: Instant)

    /** Issue new refresh token. Returns RefreshIssued containing id, plain token, and expiresAt. */
    fun issue(appId: String, appUserId: String, deviceSecretId: String, loginInstallId?: String, id: String? = null): RefreshIssued {
        val tokenPlain = Hashing.randomTokenBase64Url()
        val tokenHash = Hashing.sha256Base64Url(tokenPlain)
        val now = Instant.now()
        val exp = now.plus(ttl)

        val uuidId = id?.let { UUID.fromString(it) } ?: UUID.randomUUID()

        val input: Input<AppRefreshToken> = sql.input(AppRefreshToken::class.java) {
            set("id", uuidId)
            set("appId", UUID.fromString(appId))
            set("appUserId", UUID.fromString(appUserId))
            set("deviceSecretId", UUID.fromString(deviceSecretId))
            set("tokenHash", tokenHash)
            set("loginInstallId", loginInstallId)
            set("expiresAt", exp)
            set("revokedAt", null)
            set("replacedBy", null)
            set("createdAt", now)
            set("updatedAt", now)
        }
        val created = insert(input)
        return RefreshIssued(created.id.toString(), tokenPlain, exp)
    }

    /** Find refresh token by appId and token hash (from plain token). Returns null if not found or expired/revoked. */
    fun findByHash(appId: String, tokenPlain: String)? {
        val tokenHash = Hashing.sha256Base64Url(tokenPlain)
        val now = Instant.now()

        val all = this.findAll()
        return all.firstOrNull { rt ->
            rt.appId == UUID.fromString(appId) &&
            rt.tokenHash == tokenHash &&
            rt.revokedAt == null &&
            (rt.expiresAt?.isAfter(now) == true)
        }
    }

    /** Atomic rotate refresh token. Returns true if rotation succeeded, false otherwise. */
    fun tryRotate(appId: String, tokenHash: String, newId: String): Boolean {
        val now = Instant.now()
        val all = this.findAll()
        val index = all.findIndex { rt ->
            rt.appId == UUID.fromString(appId) &&
            rt.tokenHash == tokenHash &&
            rt.revokedAt == null
        }

        if (index == -1) return false

        val entity = all[index]
        // Check expiration
        if (entity.expiresAt?.isBefore(now) == true) return false

        // Create update input
        val input: Input<AppRefreshToken> = sql.input(AppRefreshToken::class.java) {
            set("id", entity.id)
            set("revokedAt", now)
            set("replacedBy", UUID(newId))
            set("updatedAt", now)
            // Copy other fields
            set("appId", entity.appId)
            set("appUserId", entity.appUserId)
            set("deviceSecretId", entity.deviceSecretId)
            set("tokenHash", entity.tokenHash)
            set("loginInstallId", entity.loginInstallId)
            set("expiresAt", entity.expiresAt)
            set("createdAt", entity.createdAt)
        }
        save(input)
        return true
    }

    /** Revoke all active refresh tokens for a given app user. */
    fun revokeByAppUser(appId: String, appUserId: String) {
        val now = Instant.now()
        val appIdUUID = UUID.fromString(appId)
        val appUserIdUUID = UUID.fromString(appUserId)
        val all = this.findAll()
        all.forEach { rt ->
            if (rt.appId == appIdUUID && rt.appUserId == appUserIdUUID && rt.revokedAt == null) {
                val input: Input<AppRefreshToken> = sql.input(AppRefreshToken::class.java) {
                    set("id", rt.id)
                    set("revokedAt", now)
                    set("updatedAt", now)
                    set("appId", rt.appId)
                    set("appUserId", rt.appUserId)
                    set("deviceSecretId", rt.deviceSecretId)
                    set("tokenHash", rt.tokenHash)
                    set("loginInstallId", rt.loginInstallId)
                    set("expiresAt", rt.expiresAt)
                    set("createdAt", rt.createdAt)
                }
                save(input)
            }
        }
    }

    /** Revoke all active refresh tokens associated with this device secret. */
    fun revokeByDeviceSecret(deviceSecretId: String) {
        val now = Instant.now()
        val deviceSecretIdUUID = UUID.fromString(deviceSecretId)
        val all = this.findAll()
        all.forEach { rt ->
            if (rt.deviceSecretId == deviceSecretIdUUID && rt.revokedAt == null) {
                val input: Input<AppRefreshToken> = sql.input(AppRefreshToken::class.java) {
                    set("id", rt.id)
                    set("revokedAt", now)
                    set("updatedAt", now)
                    set("appId", rt.appId)
                    set("appUserId", rt.appUserId)
                    set("deviceSecretId", rt.deviceSecretId)
                    set("tokenHash", rt.tokenHash)
                    set("loginInstallId", rt.loginInstallId)
                    set("expiresAt", rt.expiresAt)
                    set("createdAt", rt.createdAt)
                }
                save(input)
            }
        }
    }
}