package com.ifmix.api.core.common.jimmer.repository.auth

import com.ifmix.api.core.common.jimmer.base.BaseAppCrudRepository
import com.ifmix.api.core.common.jimmer.entity.auth.AppUser
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** AppUser repository with custom ensure() method */
@Component
class AppUserRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<AppUser>(sql, AppUser::class) {

    /**
     * Ensure app_user exists for (appId, authIdentityId). Returns appUserId.
     * Uses findAll + filter as a simple approach (AppScopedFilter handles appId filtering).
     */
    fun ensure(appId: UUID, authIdentityId: UUID): UUID {
        // Check if already exists
        val existing = findAll().firstOrNull { user ->
            user.appId == appId && user.authIdentity.id == authIdentityId
        }
        if (existing != null) return existing.id

        // Create new AppUser using Jimmer draft lambda
        val newUser = AppUser {
            id = UUID.randomUUID()
            this.appId = appId
            authIdentity { id = authIdentityId }
            metadata = null
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }
        return save(newUser).id
    }
}
