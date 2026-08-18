package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.jooq.CrudRepoOps
import com.ifmix.api.core.jooq.tables.CoreAppUser.Companion.CORE_APP_USER
import com.ifmix.api.core.entity.auth.AppUser
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class AppUserRepository(private val crud: CrudRepoOps) {

    fun findByAppAndIdentity(ctx: SvcCtx, appId: UUID, authIdentityId: UUID): AppUser? =
        ctx.dsl.selectFrom(CORE_APP_USER)
            .where(CORE_APP_USER.APP_ID.eq(appId))
            .and(CORE_APP_USER.AUTH_IDENTITY_ID.eq(authIdentityId))
            .fetchOneInto(AppUser::class.java)

    fun insert(ctx: SvcCtx, user: AppUser) = crud.insert(ctx, CORE_APP_USER, user)

    fun ensure(ctx: SvcCtx, appId: UUID, authIdentityId: UUID): UUID {
        val existing = findByAppAndIdentity(ctx, appId, authIdentityId)
        if (existing != null) return existing.id
        val now = Instant.now()
        val newUser = AppUser(
            id = UuidV7.generate(), appId = appId, authIdentityId = authIdentityId,
            metadata = null, createdAt = now, updatedAt = now,
        )
        insert(ctx, newUser)
        return newUser.id
    }
}
