package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.AppRefreshToken
import com.ifmix.api.core.entity.auth.appId
import com.ifmix.api.core.entity.auth.id
import com.ifmix.api.core.entity.auth.updatedAt
import com.ifmix.api.core.entity.auth.revokedAt
import com.ifmix.api.core.entity.auth.tokenHash
import com.ifmix.api.core.entity.auth.expiresAt
import com.ifmix.api.core.entity.auth.replacedBy
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.isNull
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class AppRefreshTokenRepository(sql: KSqlClient) : BaseAppCrudRepository<AppRefreshToken>(sql, AppRefreshToken::class) {

    fun findValidByHash(ctx: SvcCtx, appId: UUID, tokenHash: String): AppRefreshToken? {
        val now = Instant.now()
        return sql.createQuery(AppRefreshToken::class) {
            where(table.appId eq appId)
            where(table.tokenHash eq tokenHash)
            where(table.revokedAt.isNull)
            where(table.expiresAt.isNull.or(table.expiresAt gt now))
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun revoke(ctx: SvcCtx, id: UUID, replacedBy: UUID? = null) {
        val now = Instant.now()
        if (replacedBy != null) {
            sql.createUpdate(AppRefreshToken::class) {
                where(table.id eq id)
                set(table.revokedAt, now)
                set(table.updatedAt, now)
                set(table.replacedBy, replacedBy)
            }.execute()
        } else {
            sql.createUpdate(AppRefreshToken::class) {
                where(table.id eq id)
                set(table.revokedAt, now)
                set(table.updatedAt, now)
            }.execute()
        }
    }
}
