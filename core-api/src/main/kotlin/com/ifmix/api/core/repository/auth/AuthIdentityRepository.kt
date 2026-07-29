package com.ifmix.api.core.repository.auth

import com.ifmix.api.core.entity.auth.AuthIdentity
import com.ifmix.api.core.entity.auth.authTenantId
import com.ifmix.api.core.entity.auth.email
import com.ifmix.api.core.repository.base.BaseCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Component
import java.util.UUID

/** Auth identity repository with custom queries */
@Component
class AuthIdentityRepository(
    sql: KSqlClient,
) : BaseCrudRepository<AuthIdentity>(sql, AuthIdentity::class) {

    /** Find identity by tenant ID and email using Jimmer query DSL */
    fun findByTenantAndEmail(tenantId: String, email: String): AuthIdentity? {
        val tenantUUID = UUID.fromString(tenantId)
        return sql.createQuery(AuthIdentity::class) {
            where(table.authTenantId eq tenantUUID)
            where(table.email eq email)
            select(table)
        }.fetchOneOrNull()
    }
}
