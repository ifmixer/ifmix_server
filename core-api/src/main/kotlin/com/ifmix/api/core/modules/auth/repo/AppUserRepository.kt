package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.AppUser
import com.ifmix.api.core.entity.auth.appId
import com.ifmix.api.core.entity.auth.authIdentityId
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import com.ifmix.api.core.infra.db.UuidV7
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class AppUserRepository(sql: KSqlClient) : BaseAppCrudRepository<AppUser>(sql, AppUser::class) {

    fun findByAppAndIdentity(ctx: SvcCtx, appId: UUID, authIdentityId: UUID): AppUser? {
        return ctx.sql.createQuery(AppUser::class) {
            where(table.get<UUID>("appId") eq appId)
            where(table.authIdentityId eq authIdentityId)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun ensure(ctx: SvcCtx, appId: UUID, authIdentityId: UUID): UUID {
        val existing = findByAppAndIdentity(ctx, appId, authIdentityId)
        if (existing != null) return existing.id
        val now = Instant.now()
        val id = UuidV7.generate()
        val entity = AppUser {
            this.id = id
            this.appId = appId
            this.authIdentityId = authIdentityId
            this.createdAt = now
            this.updatedAt = now
        }
        save(ctx, entity)
        return id
    }
}
