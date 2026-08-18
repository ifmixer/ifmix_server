package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.jooq.CrudOps
import com.ifmix.api.core.jooq.tables.CoreAppRefreshToken.Companion.CORE_APP_REFRESH_TOKEN
import com.ifmix.api.core.model.AppRefreshToken
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * AppRefreshToken jOOQ repository.
 */
@Repository
class AppRefreshTokenRepository(
    private val crud: CrudOps,
) {

    fun findValidByHash(ctx: RepoContext, appId: UUID, tokenHash: String): AppRefreshToken? {
        val now = Instant.now()
        val record = ctx.dsl.selectFrom(CORE_APP_REFRESH_TOKEN)
            .where(CORE_APP_REFRESH_TOKEN.APP_ID.eq(appId))
            .and(CORE_APP_REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
            .and(CORE_APP_REFRESH_TOKEN.REVOKED_AT.isNull())
            .and(CORE_APP_REFRESH_TOKEN.EXPIRES_AT.isNull().or(CORE_APP_REFRESH_TOKEN.EXPIRES_AT.gt(now)))
            .fetchOne()
        return record?.let { toModel(it) }
    }

    fun revoke(ctx: RepoContext, id: UUID, replacedBy: UUID? = null) {
        val now = Instant.now()
        if (replacedBy != null) {
            ctx.dsl.update(CORE_APP_REFRESH_TOKEN)
                .set(CORE_APP_REFRESH_TOKEN.REVOKED_AT, now)
                .set(CORE_APP_REFRESH_TOKEN.UPDATED_AT, now)
                .set(CORE_APP_REFRESH_TOKEN.REPLACED_BY, replacedBy)
                .where(CORE_APP_REFRESH_TOKEN.ID.eq(id))
                .execute()
        } else {
            ctx.dsl.update(CORE_APP_REFRESH_TOKEN)
                .set(CORE_APP_REFRESH_TOKEN.REVOKED_AT, now)
                .set(CORE_APP_REFRESH_TOKEN.UPDATED_AT, now)
                .where(CORE_APP_REFRESH_TOKEN.ID.eq(id))
                .execute()
        }
    }

    fun insert(ctx: RepoContext, token: AppRefreshToken) {
        crud.insert(ctx, CORE_APP_REFRESH_TOKEN, token)
    }

    // =========================================================================
    // Record ↔ model helpers
    // =========================================================================

    private fun toModel(r: org.jooq.Record): AppRefreshToken = AppRefreshToken(
        id = r.get(CORE_APP_REFRESH_TOKEN.ID)!!,
        appId = r.get(CORE_APP_REFRESH_TOKEN.APP_ID)!!,
        appUserId = r.get(CORE_APP_REFRESH_TOKEN.APP_USER_ID)!!,
        deviceSecretId = r.get(CORE_APP_REFRESH_TOKEN.DEVICE_SECRET_ID),
        tokenHash = r.get(CORE_APP_REFRESH_TOKEN.TOKEN_HASH)!!,
        loginInstallId = r.get(CORE_APP_REFRESH_TOKEN.LOGIN_INSTALL_ID),
        expiresAt = r.get(CORE_APP_REFRESH_TOKEN.EXPIRES_AT)!!,
        revokedAt = r.get(CORE_APP_REFRESH_TOKEN.REVOKED_AT),
        replacedBy = r.get(CORE_APP_REFRESH_TOKEN.REPLACED_BY),
        createdAt = r.get(CORE_APP_REFRESH_TOKEN.CREATED_AT) ?: Instant.now(),
        updatedAt = r.get(CORE_APP_REFRESH_TOKEN.UPDATED_AT),
    )
}
