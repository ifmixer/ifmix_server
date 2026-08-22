package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.AppUserRefreshToken
import com.ifmix.api.core.entity.auth.appId
import com.ifmix.api.core.entity.auth.expiresAt
import com.ifmix.api.core.entity.auth.id
import com.ifmix.api.core.entity.auth.replacedBy
import com.ifmix.api.core.entity.auth.revokedAt
import com.ifmix.api.core.entity.auth.tokenHash
import com.ifmix.api.core.entity.auth.updatedAt
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.AppCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.gt
import org.babyfish.jimmer.sql.kt.ast.expression.isNull
import org.babyfish.jimmer.sql.kt.ast.expression.or
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class AppUserRefreshTokenRepository {
    companion object { private val tpl = AppCrudRepoTemplate(AppUserRefreshToken::class) }

    fun findValidByHash(mc: ModuleCtx, appId: UUID, tokenHash: String): AppUserRefreshToken? {
        val now = Instant.now()
        return mc.sql.createQuery(AppUserRefreshToken::class) {
            where(table.appId eq appId)
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

    fun revoke(mc: ModuleCtx, id: UUID, replacedBy: UUID? = null): Int {
        return mc.sql.createUpdate(AppUserRefreshToken::class) {
            where(table.id eq id)
            set(table.revokedAt, Instant.now())
            set(table.updatedAt, Instant.now())
            if (replacedBy != null) set(table.replacedBy, replacedBy)
        }.execute()
    }

    fun save(mc: ModuleCtx, entity: AppUserRefreshToken) = tpl.save(mc, entity)
}
