package com.ifmix.api.core.common.jimmer.repository.auth

import com.ifmix.api.core.common.jimmer.base.BaseAppCrudRepository
import com.ifmix.api.core.common.jimmer.entity.auth.AppUser
import org.babyfish.jimmer.Input
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
     * Ensure app_user exists for (appId, authIdentityId). Returns appUserId as String.
     */
    fun ensure(appId: String, authIdentityId: String): String {
        val appIdUUID = UUID.fromString(appId)
        val identityId = UUID.fromString(authIdentityId)

        // Check if already exists
        val all = this.findAll()
        val existing = all.firstOrNull { it.appId == appIdUUID && it.authIdentity.id == identityId }
        if (existing != null) return existing.id.toString()

        // Create new AppUser using Input API
        val now = Instant.now()
        val userId = UUID.randomUUID().toString()
        val input: Input<AppUser> = sql.input(AppUser::class.java) {
            set("id", userId)
            set("appId", appIdUUID)
            set("authIdentityId", identityId)
            set("metadata", emptyMap())
            set("createdAt", now)
            set("updatedAt", now)
        }
        return insert(input).id.toString()
    }
}