package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.AppRefreshToken
import com.ifmix.api.core.entity.auth.appId
import com.ifmix.api.core.entity.auth.expiresAt
import com.ifmix.api.core.entity.auth.id
import com.ifmix.api.core.entity.auth.revokedAt
import com.ifmix.api.core.entity.auth.replacedBy
import com.ifmix.api.core.entity.auth.tokenHash
import com.ifmix.api.core.entity.auth.updatedAt
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
class AppRefreshTokenRepository {
    companion object { private val tpl = CrudRepoTemplate(AppRefreshToken::class, appId = "appId") }

    fun findValidByHash(mc: ModuleCtx, appId: UUID, tokenHash: String): AppRefreshToken? {
        val now = Instant.now()
        return mc.sql.createQuery(AppRefreshToken::class) {
            where(table.get<UUID>("appId") eq appId)
            where(table.tokenHash eq tokenHash)
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

    fun revoke(mc: ModuleCtx, id: UUID, replacedBy: UUID? = null) {
        val now = Instant.now()
        if (replacedBy != null) {
            mc.sql.createUpdate(AppRefreshToken::class) {
                where(table.id eq id)
                set(table.revokedAt, now)
                set(table.updatedAt, now)
                set(table.replacedBy, replacedBy)
            }.execute()
        } else {
            mc.sql.createUpdate(AppRefreshToken::class) {
                where(table.id eq id)
                set(table.revokedAt, now)
                set(table.updatedAt, now)
            }.execute()
        }
    }

    fun save(mc: ModuleCtx, entity: AppRefreshToken) = tpl.save(mc, entity)
    fun findById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.findById(mc, appId, id)
}
