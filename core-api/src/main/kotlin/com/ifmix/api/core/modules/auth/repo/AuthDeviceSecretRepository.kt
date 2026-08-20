package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.AuthDeviceSecret
import com.ifmix.api.core.entity.auth.id
import com.ifmix.api.core.entity.auth.secretHash
import com.ifmix.api.core.entity.auth.updatedAt
import com.ifmix.api.core.entity.auth.lastUsedAt
import com.ifmix.api.core.entity.auth.revokedAt
import com.ifmix.api.core.entity.auth.expiresAt
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.CrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.gt
import org.babyfish.jimmer.sql.kt.ast.expression.isNull
import org.babyfish.jimmer.sql.kt.ast.expression.or
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class AuthDeviceSecretRepository {
    companion object { private val tpl = CrudRepoTemplate(AuthDeviceSecret::class) }

    fun findValidByHash(mc: ModuleCtx, secretHash: String): AuthDeviceSecret? {
        val now = Instant.now()
        return mc.sql.createQuery(AuthDeviceSecret::class) {
            where(table.secretHash eq secretHash)
            where(table.revokedAt.isNull())
            where(
                or(
                    table.expiresAt.isNull(),
                    table.expiresAt gt now
                )
            )
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun touch(mc: ModuleCtx, id: UUID) {
        mc.sql.createUpdate(AuthDeviceSecret::class) {
            where(table.id eq id)
            set(table.lastUsedAt, Instant.now())
            set(table.updatedAt, Instant.now())
        }.execute()
    }

    fun revoke(mc: ModuleCtx, id: UUID) {
        mc.sql.createUpdate(AuthDeviceSecret::class) {
            where(table.id eq id)
            set(table.revokedAt, Instant.now())
            set(table.updatedAt, Instant.now())
        }.execute()
    }

    fun save(mc: ModuleCtx, entity: AuthDeviceSecret) = tpl.save(mc, entity)
}
