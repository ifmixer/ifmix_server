package com.ifmix.core.api.modules.auth.repo

import com.ifmix.core.api.entity.auth.RefreshToken
import com.ifmix.core.api.entity.auth.actorId
import com.ifmix.core.api.entity.auth.actorType
import com.ifmix.core.api.entity.auth.projectId
import com.ifmix.core.api.entity.auth.expiresAt
import com.ifmix.core.api.entity.auth.id
import com.ifmix.core.api.entity.auth.replacedBy
import com.ifmix.core.api.entity.auth.revokedAt
import com.ifmix.core.api.entity.auth.tokenHash
import com.ifmix.core.api.entity.auth.updatedAt
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.gt
import org.babyfish.jimmer.sql.kt.ast.expression.isNull
import org.babyfish.jimmer.sql.kt.ast.expression.or
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class RefreshTokenRepository {
    companion object { private val tpl = ProjectCrudRepoTemplate(RefreshToken::class) }

    fun findValidByHash(mc: ModuleCtx, projectId: UUID, tokenHash: String): RefreshToken? {
        val now = Instant.now()
        return mc.sql.createQuery(RefreshToken::class) {
            where(table.projectId eq projectId)
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
        return mc.sql.createUpdate(RefreshToken::class) {
            where(table.id eq id)
            set(table.revokedAt, Instant.now())
            set(table.updatedAt, Instant.now())
            if (replacedBy != null) set(table.replacedBy, replacedBy)
        }.execute()
    }

    fun save(mc: ModuleCtx, entity: RefreshToken) = tpl.save(mc, entity)

    /**
     * 阶段 6 清理判定：某主体（actorId+actorType）名下是否存在有效 refresh token。
     * 有效 = revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())。
     * 无有效 token 的匿名 customer 即可物理删（token 过期后客户端无法再 attach，复活无意义）。
     */
    fun hasValidToken(mc: ModuleCtx, projectId: UUID, actorId: UUID, actorType: Int): Boolean {
        val now = Instant.now()
        return mc.sql.createQuery(RefreshToken::class) {
            where(table.projectId eq projectId)
            where(table.actorId eq actorId)
            where(table.actorType eq actorType)
            where(table.revokedAt.isNull())
            where(
                or(
                    table.expiresAt.isNull(),
                    table.expiresAt gt now
                )
            )
            select(table.id)
        }.limit(1).execute().isNotEmpty()
    }

    /** 合并：吊销某主体（cur）所有未吊销的 refresh token。返回吊销行数。 */
    fun revokeAllByActor(mc: ModuleCtx, projectId: UUID, actorId: UUID, actorType: Int): Int {
        val now = Instant.now()
        return mc.sql.createUpdate(RefreshToken::class) {
            where(table.projectId eq projectId)
            where(table.actorId eq actorId)
            where(table.actorType eq actorType)
            where(table.revokedAt.isNull())
            set(table.revokedAt, now)
            set(table.updatedAt, now)
        }.execute()
    }
}
