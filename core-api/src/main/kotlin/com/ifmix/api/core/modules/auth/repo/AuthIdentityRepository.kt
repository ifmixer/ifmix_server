package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.AuthIdentity
import com.ifmix.api.core.entity.auth.authTenant
import com.ifmix.api.core.entity.auth.email
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.repo.BaseCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AuthIdentityRepository(sql: KSqlClient) : BaseCrudRepository<AuthIdentity>(sql, AuthIdentity::class) {

    fun findByTenantAndEmail(ctx: SvcCtx, tenantId: UUID, email: String): AuthIdentity? {
        return sql.createQuery(AuthIdentity::class) {
            where(table.authTenant eq tenantId)
            where(table.email eq email)
            select(table)
        }.limit(1).execute().firstOrNull()
    }
}
