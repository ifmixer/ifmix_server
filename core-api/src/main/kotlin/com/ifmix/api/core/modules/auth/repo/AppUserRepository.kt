package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.AppUser
import com.ifmix.api.core.entity.auth.appId
import com.ifmix.api.core.entity.auth.authIdentity
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AppUserRepository(sql: KSqlClient) : BaseAppCrudRepository<AppUser>(sql, AppUser::class) {

    fun findByAppAndIdentity(ctx: SvcCtx, appId: UUID, authIdentityId: UUID): AppUser? {
        return sql.createQuery(AppUser::class) {
            where(table.appId eq appId)
            where(table.authIdentity eq authIdentityId)
            select(table)
        }.limit(1).execute().firstOrNull()
    }
}
