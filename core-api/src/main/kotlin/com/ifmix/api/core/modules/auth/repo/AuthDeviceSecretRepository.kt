package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.jooq.CrudOps
import com.ifmix.api.core.jooq.tables.CoreAuthDeviceSecret.Companion.CORE_AUTH_DEVICE_SECRET
import com.ifmix.api.core.model.AuthDeviceSecret
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * AuthDeviceSecret jOOQ repository.
 */
@Repository
class AuthDeviceSecretRepository(
    private val crud: CrudOps,
) {

    fun findValidByHash(ctx: RepoContext, secretHash: String): AuthDeviceSecret? {
        val now = Instant.now()
        val record = ctx.dsl.selectFrom(CORE_AUTH_DEVICE_SECRET)
            .where(CORE_AUTH_DEVICE_SECRET.SECRET_HASH.eq(secretHash))
            .and(CORE_AUTH_DEVICE_SECRET.REVOKED_AT.isNull())
            .and(CORE_AUTH_DEVICE_SECRET.EXPIRES_AT.isNull().or(CORE_AUTH_DEVICE_SECRET.EXPIRES_AT.greaterThan(now)))
            .fetchOne()
        return record?.let { toModel(it) }
    }

    fun touch(ctx: RepoContext, id: UUID) {
        val now = Instant.now()
        ctx.dsl.update(CORE_AUTH_DEVICE_SECRET)
            .set(CORE_AUTH_DEVICE_SECRET.LAST_USED_AT, now)
            .set(CORE_AUTH_DEVICE_SECRET.UPDATED_AT, now)
            .where(CORE_AUTH_DEVICE_SECRET.ID.eq(id))
            .execute()
    }

    fun revoke(ctx: RepoContext, id: UUID) {
        val now = Instant.now()
        ctx.dsl.update(CORE_AUTH_DEVICE_SECRET)
            .set(CORE_AUTH_DEVICE_SECRET.REVOKED_AT, now)
            .set(CORE_AUTH_DEVICE_SECRET.UPDATED_AT, now)
            .where(CORE_AUTH_DEVICE_SECRET.ID.eq(id))
            .execute()
    }

    fun insert(ctx: RepoContext, secret: AuthDeviceSecret) {
        crud.insert(ctx, CORE_AUTH_DEVICE_SECRET, secret)
    }

    // =========================================================================
    // Record ↔ model helpers
    // =========================================================================

    private fun toModel(r: org.jooq.Record): AuthDeviceSecret = AuthDeviceSecret(
        id = r.get(CORE_AUTH_DEVICE_SECRET.ID)!!,
        authTenantId = r.get(CORE_AUTH_DEVICE_SECRET.AUTH_TENANT_ID)!!,
        authIdentityId = r.get(CORE_AUTH_DEVICE_SECRET.AUTH_IDENTITY_ID)!!,
        secretHash = r.get(CORE_AUTH_DEVICE_SECRET.SECRET_HASH)!!,
        loginInstallId = r.get(CORE_AUTH_DEVICE_SECRET.LOGIN_INSTALL_ID),
        expiresAt = r.get(CORE_AUTH_DEVICE_SECRET.EXPIRES_AT)!!,
        revokedAt = r.get(CORE_AUTH_DEVICE_SECRET.REVOKED_AT),
        lastUsedAt = r.get(CORE_AUTH_DEVICE_SECRET.LAST_USED_AT),
        createdAt = r.get(CORE_AUTH_DEVICE_SECRET.CREATED_AT) ?: Instant.now(),
        updatedAt = r.get(CORE_AUTH_DEVICE_SECRET.UPDATED_AT),
    )
}
