package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.AuthDeviceSecret
import com.ifmix.api.core.entity.auth.expiresAt
import com.ifmix.api.core.entity.auth.id
import com.ifmix.api.core.entity.auth.lastUsedAt
import com.ifmix.api.core.entity.auth.revokedAt
import com.ifmix.api.core.entity.auth.secretHash
import com.ifmix.api.core.entity.auth.updatedAt
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.repo.BaseCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/** Device secret repository with custom queries */
@Repository
class AuthDeviceSecretRepository(sql: KSqlClient,) : BaseCrudRepository<AuthDeviceSecret>(sql, AuthDeviceSecret::class) {

    /**
     * Find a valid (not expired, not revoked) device secret by its hash.
     */
    fun findValidByHash(ctx: OperationContext, secretHash: String): AuthDeviceSecret? {
        val now = Instant.now()
        return sql.createQuery(AuthDeviceSecret::class) {
            where(table.secretHash eq secretHash)
            where(table.revokedAt.isNull())
            where(or(table.expiresAt.isNull(), table.expiresAt gt now))
            select(table)
        }.fetchOneOrNull()
    }

    /**
     * Touch: update lastUsedAt to now.
     */
    fun touch(ctx: OperationContext, id: UUID) {
        sql.createUpdate(AuthDeviceSecret::class) {
            set(table.lastUsedAt, Instant.now())
            set(table.updatedAt, Instant.now())
            where(table.id eq id)
        }.execute()
    }

    /**
     * Revoke a device secret by setting revokedAt.
     */
    fun revoke(ctx: OperationContext, id: UUID) {
        sql.createUpdate(AuthDeviceSecret::class) {
            set(table.revokedAt, Instant.now())
            set(table.updatedAt, Instant.now())
            where(table.id eq id)
        }.execute()
    }
}
