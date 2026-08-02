package com.ifmix.api.core.repository.auth

import com.ifmix.api.core.entity.auth.UserInstallBinding
import com.ifmix.api.core.entity.auth.appId
import com.ifmix.api.core.entity.auth.clientIp
import com.ifmix.api.core.entity.auth.clientPlatform
import com.ifmix.api.core.entity.auth.installId
import com.ifmix.api.core.entity.auth.lastSeenAt
import com.ifmix.api.core.entity.auth.loginCount
import com.ifmix.api.core.entity.auth.updatedAt
import com.ifmix.api.core.entity.auth.userId
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * 用户-Install 绑定 Repository。
 */
@Repository
class UserInstallBindingRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<UserInstallBinding>(sql, UserInstallBinding::class) {

    /**
     * 记录一次登录绑定（幂等 upsert）：
     * - 不存在 → 插入，loginCount=1
     * - 已存在 → 更新 lastSeenAt、loginCount++、clientIp/platform
     */
    fun recordBinding(
        repoCtx: RepoContext,
        appId: UUID,
        userId: UUID,
        installId: UUID,
        clientIp: String?,
        clientPlatform: String?,
    ) {
        val now = Instant.now()
        val existing = sql.createQuery(UserInstallBinding::class) {
            where(table.appId eq appId)
            where(table.userId eq userId)
            where(table.installId eq installId)
            select(table)
        }.fetchOneOrNull()

        if (existing == null) {
            val entity = UserInstallBinding {
                id = UuidV7.generate()
                this.appId = appId
                this.userId = userId
                this.installId = installId
                this.firstSeenAt = now
                this.lastSeenAt = now
                this.loginCount = 1
                this.clientIp = clientIp
                this.clientPlatform = clientPlatform
                this.createdAt = now
                this.updatedAt = now
            }
            save(repoCtx, entity)
        } else {
            sql.createUpdate(UserInstallBinding::class) {
                set(table.lastSeenAt, now)
                set(table.loginCount, table.loginCount + 1)
                set(table.updatedAt, now)
                if (clientIp != null) set(table.clientIp, clientIp)
                if (clientPlatform != null) set(table.clientPlatform, clientPlatform)
                where(table.appId eq appId)
                where(table.userId eq userId)
                where(table.installId eq installId)
            }.execute()
        }
    }
}
