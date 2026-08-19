package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.AuthDeviceSecret
import com.ifmix.api.core.entity.auth.id
import com.ifmix.api.core.entity.auth.secretHash
import com.ifmix.api.core.entity.auth.updatedAt
import com.ifmix.api.core.entity.auth.lastUsedAt
import com.ifmix.api.core.entity.auth.revokedAt
import com.ifmix.api.core.entity.auth.expiresAt
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.repo.BaseCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.gt
import org.babyfish.jimmer.sql.kt.ast.expression.isNull
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class AuthDeviceSecretRepository(sql: KSqlClient) : BaseCrudRepository<AuthDeviceSecret>(sql, AuthDeviceSecret::class) {

    fun findValidByHash(ctx: SvcCtx, secretHash: String): AuthDeviceSecret? {
        val now = Instant.now()
        return sql.createQuery(AuthDeviceSecret::class) {
            where(table.secretHash eq secretHash)
            where(table.revokedAt.isNull)
            where(table.expiresAt.isNull.or(table.expiresAt gt now))
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun touch(ctx: SvcCtx, id: UUID) {
        sql.createUpdate(AuthDeviceSecret::class) {
            where(table.id eq id)
            set(table.lastUsedAt, Instant.now())
            set(table.updatedAt, Instant.now())
        }.execute()
    }

    fun revoke(ctx: SvcCtx, id: UUID) {
        sql.createUpdate(AuthDeviceSecret::class) {
            where(table.id eq id)
            set(table.revokedAt, Instant.now())
            set(table.updatedAt, Instant.now())
        }.execute()
    }
}
