package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.jooq.CrudRepoOpsFactory
import com.ifmix.api.core.jooq.tables.CoreAppUser.Companion.CORE_APP_USER
import com.ifmix.api.core.entity.auth.AppUser
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class AppUserRepository(factory: CrudRepoOpsFactory) {

    private val crud = factory.create(
        table = CORE_APP_USER,
        idField = CORE_APP_USER.ID,
        appIdField = CORE_APP_USER.APP_ID,
        type = AppUser::class.java,
    )

    fun findByAppAndIdentity(ctx: SvcCtx, appId: UUID, authIdentityId: UUID): AppUser? =
        ctx.dsl.selectFrom(CORE_APP_USER)
            .where(CORE_APP_USER.APP_ID.eq(appId))
            .and(CORE_APP_USER.AUTH_IDENTITY_ID.eq(authIdentityId))
            .fetchOneInto(AppUser::class.java)

    fun insert(ctx: SvcCtx, user: AppUser) = crud.insert(ctx, user)

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
