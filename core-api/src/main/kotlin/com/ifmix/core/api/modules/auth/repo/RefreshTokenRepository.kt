package com.ifmix.core.api.modules.auth.repo

import com.ifmix.core.api.entity.auth.RefreshToken
import com.ifmix.core.api.entity.auth.actorId
import com.ifmix.core.api.entity.auth.actorType
import com.ifmix.core.api.entity.auth.projectId
import com.ifmix.core.api.entity.auth.id
import com.ifmix.core.api.entity.auth.replacedBy
import com.ifmix.core.api.entity.auth.revokedAt
import com.ifmix.core.api.entity.auth.tokenHash
import com.ifmix.core.api.entity.auth.updatedAt
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.isNull
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class RefreshTokenRepository {
    companion object { private val tpl = ProjectCrudRepoTemplate(RefreshToken::class, UUID::class) }

    fun findValidByHash(mc: ModuleCtx, projectId: String, tokenHash: String): RefreshToken? {
        // 有效性只看 revoked_at：expires_at 不作为强制失效条件（过期未吊销的 token 仍可 refresh），
        // 生命周期由回收/清理策略接管（见 RefreshTokenRepositoryTest）。
        return mc.sql.createQuery(RefreshToken::class) {
            where(table.projectId eq projectId)
            where(table.tokenHash eq tokenHash)
            where(table.revokedAt.isNull())
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
     * 有效 = revoked_at IS NULL（expires_at 不参与判定：过期未吊销仍算有效，
     * 与 findValidByHash 一致，防止「清理删了人、token 却还能 refresh」的复活窗口）。
     */
    fun hasValidToken(mc: ModuleCtx, projectId: String, actorId: UUID, actorType: Int): Boolean {
        return mc.sql.createQuery(RefreshToken::class) {
            where(table.projectId eq projectId)
            where(table.actorId eq actorId)
            where(table.actorType eq actorType)
            where(table.revokedAt.isNull())
            select(table.id)
        }.limit(1).execute().isNotEmpty()
    }

    /** 合并：吊销某主体（cur）所有未吊销的 refresh token。返回吊销行数。 */
    fun revokeAllByActor(mc: ModuleCtx, projectId: String, actorId: UUID, actorType: Int): Int {
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
