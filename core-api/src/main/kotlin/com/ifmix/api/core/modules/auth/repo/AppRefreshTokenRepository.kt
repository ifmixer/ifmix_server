package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOpsFactory
import com.ifmix.api.core.jooq.tables.CoreAppRefreshToken.Companion.CORE_APP_REFRESH_TOKEN
import com.ifmix.api.core.entity.auth.AppRefreshToken
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * AppRefreshToken jOOQ repository.
 */
@Repository
class AppRefreshTokenRepository(factory: CrudRepoOpsFactory) {

    private val crud = factory.create(
        table = CORE_APP_REFRESH_TOKEN,
        idField = CORE_APP_REFRESH_TOKEN.ID,
        appIdField = CORE_APP_REFRESH_TOKEN.APP_ID,
        type = AppRefreshToken::class.java,
    )

    fun findValidByHash(ctx: SvcCtx, appId: UUID, tokenHash: String): AppRefreshToken? =
        ctx.dsl.selectFrom(CORE_APP_REFRESH_TOKEN)
            .where(CORE_APP_REFRESH_TOKEN.APP_ID.eq(appId))
            .and(CORE_APP_REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
            .and(CORE_APP_REFRESH_TOKEN.REVOKED_AT.isNull())
            .and(CORE_APP_REFRESH_TOKEN.EXPIRES_AT.isNull().or(CORE_APP_REFRESH_TOKEN.EXPIRES_AT.gt(Instant.now())))
            .fetchOneInto(AppRefreshToken::class.java)

    fun revoke(ctx: SvcCtx, id: UUID, replacedBy: UUID? = null) {
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

    fun insert(ctx: SvcCtx, token: AppRefreshToken) {
        crud.insert(ctx, token)
    }
}
