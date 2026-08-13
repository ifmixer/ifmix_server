package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.AuthIdentity
import com.ifmix.api.core.entity.auth.authTenantId
import com.ifmix.api.core.entity.auth.email
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.jimmer.ClusterRegistry
import com.ifmix.api.core.infra.repo.BaseCrudRepository
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID

/** Auth identity repository with custom queries */
@Repository
class AuthIdentityRepository(
    clusterRegistry: ClusterRegistry,
) : BaseCrudRepository<AuthIdentity>(clusterRegistry, AuthIdentity::class) {

    /** Find identity by tenant ID and email using Jimmer query DSL */
    fun findByTenantAndEmail(ctx: RepoContext, tenantId: String, email: String): AuthIdentity? {
        val tenantUUID = UUID.fromString(tenantId)
        return sql(ctx).createQuery(AuthIdentity::class) {
            where(table.authTenantId eq tenantUUID)
            where(table.email eq email)
            select(table)
        }.fetchOneOrNull()
    }
}
