package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOpsFactory
import com.ifmix.api.core.jooq.tables.CoreAuthDeviceSecret.Companion.CORE_AUTH_DEVICE_SECRET
import com.ifmix.api.core.entity.auth.AuthDeviceSecret
import org.jooq.TableField
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * AuthDeviceSecret jOOQ repository.
 */
@Repository
class AuthDeviceSecretRepository(factory: CrudRepoOpsFactory) {

    companion object {
        val FIELD_MAP: Map<String, TableField<*, *>> = mapOf(
            AuthDeviceSecret::id.name to CORE_AUTH_DEVICE_SECRET.ID,
            AuthDeviceSecret::authTenantId.name to CORE_AUTH_DEVICE_SECRET.AUTH_TENANT_ID,
            AuthDeviceSecret::authIdentityId.name to CORE_AUTH_DEVICE_SECRET.AUTH_IDENTITY_ID,
            AuthDeviceSecret::loginInstallId.name to CORE_AUTH_DEVICE_SECRET.LOGIN_INSTALL_ID,
            AuthDeviceSecret::expiresAt.name to CORE_AUTH_DEVICE_SECRET.EXPIRES_AT,
            AuthDeviceSecret::revokedAt.name to CORE_AUTH_DEVICE_SECRET.REVOKED_AT,
            AuthDeviceSecret::lastUsedAt.name to CORE_AUTH_DEVICE_SECRET.LAST_USED_AT,
            AuthDeviceSecret::createdAt.name to CORE_AUTH_DEVICE_SECRET.CREATED_AT,
            AuthDeviceSecret::updatedAt.name to CORE_AUTH_DEVICE_SECRET.UPDATED_AT,
        )
    }

    private val crud = factory.create(
        table = CORE_AUTH_DEVICE_SECRET,
        idField = CORE_AUTH_DEVICE_SECRET.ID,
        appIdField = null,
        type = AuthDeviceSecret::class.java,
    )

    fun findValidByHash(ctx: SvcCtx, secretHash: String): AuthDeviceSecret? =
        ctx.dsl.selectFrom(CORE_AUTH_DEVICE_SECRET)
            .where(CORE_AUTH_DEVICE_SECRET.SECRET_HASH.eq(secretHash))
            .and(CORE_AUTH_DEVICE_SECRET.REVOKED_AT.isNull())
            .and(CORE_AUTH_DEVICE_SECRET.EXPIRES_AT.isNull().or(CORE_AUTH_DEVICE_SECRET.EXPIRES_AT.greaterThan(Instant.now())))
            .fetchOneInto(AuthDeviceSecret::class.java)

    fun touch(ctx: SvcCtx, id: UUID) {
        val now = Instant.now()
        ctx.dsl.update(CORE_AUTH_DEVICE_SECRET)
            .set(CORE_AUTH_DEVICE_SECRET.LAST_USED_AT, now)
            .set(CORE_AUTH_DEVICE_SECRET.UPDATED_AT, now)
            .where(CORE_AUTH_DEVICE_SECRET.ID.eq(id))
            .execute()
    }

    fun revoke(ctx: SvcCtx, id: UUID) {
        val now = Instant.now()
        ctx.dsl.update(CORE_AUTH_DEVICE_SECRET)
            .set(CORE_AUTH_DEVICE_SECRET.REVOKED_AT, now)
            .set(CORE_AUTH_DEVICE_SECRET.UPDATED_AT, now)
            .where(CORE_AUTH_DEVICE_SECRET.ID.eq(id))
            .execute()
    }

    fun insert(ctx: SvcCtx, secret: AuthDeviceSecret) {
        crud.insert(ctx, secret)
    }
}
