package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.jooq.CrudRepoOps
import com.ifmix.api.core.jooq.tables.CoreAppUser.Companion.CORE_APP_USER
import com.ifmix.api.core.model.AppUser
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * AppUser jOOQ repository.
 */
@Repository
class AppUserRepository(
    private val crud: CrudRepoOps,
) {

    fun findByAppAndIdentity(ctx: RepoContext, appId: UUID, authIdentityId: UUID): AppUser? {
        val record = ctx.dsl.selectFrom(CORE_APP_USER)
            .where(CORE_APP_USER.APP_ID.eq(appId))
            .and(CORE_APP_USER.AUTH_IDENTITY_ID.eq(authIdentityId))
            .fetchOne()
        return record?.let { toModel(it) }
    }

    fun insert(ctx: RepoContext, user: AppUser) {
        crud.insert(ctx, CORE_APP_USER, user)
    }

    /**
     * Ensure app_user exists for (appId, authIdentityId). Returns appUserId.
     */
    fun ensure(ctx: RepoContext, appId: UUID, authIdentityId: UUID): UUID {
        val existing = findByAppAndIdentity(ctx, appId, authIdentityId)
        if (existing != null) return existing.id

        val now = Instant.now()
        val newUser = AppUser(
            id = UuidV7.generate(),
            appId = appId,
            authIdentityId = authIdentityId,
            metadata = null,
            createdAt = now,
            updatedAt = now,
        )
        insert(ctx, newUser)
        return newUser.id
    }

    // =========================================================================
    // JSON ↔ model helpers
    // =========================================================================

    private fun toModel(r: org.jooq.Record): AppUser = AppUser(
        id = r.get(CORE_APP_USER.ID)!!,
        appId = r.get(CORE_APP_USER.APP_ID)!!,
        authIdentityId = r.get(CORE_APP_USER.AUTH_IDENTITY_ID)!!,
        metadata = r.get(CORE_APP_USER.METADATA)?.toString(),
        createdAt = r.get(CORE_APP_USER.CREATED_AT) ?: Instant.now(),
        updatedAt = r.get(CORE_APP_USER.UPDATED_AT),
    )
}
