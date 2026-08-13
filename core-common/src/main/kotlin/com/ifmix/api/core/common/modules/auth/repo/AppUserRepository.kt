package com.ifmix.api.core.common.modules.auth.repo

import com.ifmix.api.core.common.entity.auth.AppUser
import com.ifmix.api.core.common.entity.auth.appId
import com.ifmix.api.core.common.entity.auth.authIdentityId
import com.ifmix.api.core.common.infra.db.RepoContext
import com.ifmix.api.core.common.infra.db.UuidV7
import com.ifmix.api.core.common.infra.jimmer.ClusterRegistry
import com.ifmix.api.core.common.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/** AppUser repository with custom ensure() method */
@Repository
class AppUserRepository(
    clusterRegistry: ClusterRegistry,
) : BaseAppCrudRepository<AppUser>(clusterRegistry, AppUser::class) {

    /** Find AppUser by appId and authIdentityId using Jimmer query DSL */
    fun findByAppAndIdentity(ctx: RepoContext, appId: UUID, authIdentityId: UUID): AppUser? {
        return sql(ctx).createQuery(AppUser::class) {
            where(table.appId eq appId)
            where(table.authIdentityId eq authIdentityId)
            select(table)
        }.fetchOneOrNull()
    }

    /**
     * Ensure app_user exists for (appId, authIdentityId). Returns appUserId.
     */
    fun ensure(ctx: RepoContext, appId: UUID, authIdentityId: UUID): UUID {
        val existing = findByAppAndIdentity(ctx, appId, authIdentityId)
        if (existing != null) return existing.id

        // Create new AppUser using Jimmer draft lambda
        val newUser = AppUser {
            id = UuidV7.generate()
            this.appId = appId
            authIdentity { id = authIdentityId }
            metadata = null
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }
        return save(ctx, newUser).id
    }
}
