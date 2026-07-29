package com.ifmix.api.core.repository.auth

import com.ifmix.api.core.entity.auth.AppUser
import com.ifmix.api.core.entity.auth.appId
import com.ifmix.api.core.entity.auth.authIdentityId
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** AppUser repository with custom ensure() method */
@Component
class AppUserRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<AppUser>(sql, AppUser::class) {

    /** Find AppUser by appId and authIdentityId using Jimmer query DSL */
    fun findByAppAndIdentity(appId: UUID, authIdentityId: UUID): AppUser? {
        return sql.createQuery(AppUser::class) {
            where(table.appId eq appId)
            where(table.authIdentityId eq authIdentityId)
            select(table)
        }.fetchOneOrNull()
    }

    /**
     * Ensure app_user exists for (appId, authIdentityId). Returns appUserId.
     */
    fun ensure(appId: UUID, authIdentityId: UUID): UUID {
        val existing = findByAppAndIdentity(appId, authIdentityId)
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
