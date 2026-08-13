package com.ifmix.api.core.common.modules.auth.repo

import com.ifmix.api.core.common.entity.auth.AuthDeviceSecret
import com.ifmix.api.core.common.entity.auth.expiresAt
import com.ifmix.api.core.common.entity.auth.id
import com.ifmix.api.core.common.entity.auth.lastUsedAt
import com.ifmix.api.core.common.entity.auth.revokedAt
import com.ifmix.api.core.common.entity.auth.secretHash
import com.ifmix.api.core.common.entity.auth.updatedAt
import com.ifmix.api.core.common.infra.db.RepoContext
import com.ifmix.api.core.common.infra.jimmer.ClusterRegistry
import com.ifmix.api.core.common.infra.repo.BaseCrudRepository
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/** Device secret repository with custom queries */
@Repository
class AuthDeviceSecretRepository(
    clusterRegistry: ClusterRegistry,
) : BaseCrudRepository<AuthDeviceSecret>(clusterRegistry, AuthDeviceSecret::class) {

    /**
     * Find a valid (not expired, not revoked) device secret by its hash.
     */
    fun findValidByHash(ctx: RepoContext, secretHash: String): AuthDeviceSecret? {
        val now = Instant.now()
        return sql(ctx).createQuery(AuthDeviceSecret::class) {
            where(table.secretHash eq secretHash)
            where(table.revokedAt.isNull())
            where(or(table.expiresAt.isNull(), table.expiresAt gt now))
            select(table)
        }.fetchOneOrNull()
    }

    /**
     * Touch: update lastUsedAt to now.
     */
    fun touch(ctx: RepoContext, id: UUID) {
        writerSql(ctx).createUpdate(AuthDeviceSecret::class) {
            set(table.lastUsedAt, Instant.now())
            set(table.updatedAt, Instant.now())
            where(table.id eq id)
        }.execute()
    }

    /**
     * Revoke a device secret by setting revokedAt.
     */
    fun revoke(ctx: RepoContext, id: UUID) {
        writerSql(ctx).createUpdate(AuthDeviceSecret::class) {
            set(table.revokedAt, Instant.now())
            set(table.updatedAt, Instant.now())
            where(table.id eq id)
        }.execute()
    }
}
