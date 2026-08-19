package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.UserInstallBinding
import com.ifmix.api.core.entity.auth.appId
import com.ifmix.api.core.entity.auth.lastSeenAt
import com.ifmix.api.core.entity.auth.id
import com.ifmix.api.core.entity.auth.updatedAt
import com.ifmix.api.core.entity.auth.clientPlatform
import com.ifmix.api.core.entity.auth.clientIp
import com.ifmix.api.core.entity.auth.userId
import com.ifmix.api.core.entity.auth.installId
import com.ifmix.api.core.entity.auth.loginCount
import com.ifmix.api.core.entity.auth.UserInstallBinding.appId
import com.ifmix.api.core.entity.auth.UserInstallBinding.userId
import com.ifmix.api.core.entity.auth.UserInstallBinding.installId
import com.ifmix.api.core.entity.auth.UserInstallBinding.id
import com.ifmix.api.core.entity.auth.UserInstallBinding.lastSeenAt
import com.ifmix.api.core.entity.auth.UserInstallBinding.loginCount
import com.ifmix.api.core.entity.auth.UserInstallBinding.updatedAt
import com.ifmix.api.core.entity.auth.UserInstallBinding.clientIp
import com.ifmix.api.core.entity.auth.UserInstallBinding.clientPlatform
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class UserInstallBindingRepository(sql: KSqlClient) : BaseAppCrudRepository<UserInstallBinding>(sql, UserInstallBinding::class) {

    fun recordBinding(
        ctx: SvcCtx,
        appId: UUID,
        userId: UUID,
        installId: UUID,
        clientIp: String?,
        clientPlatform: String?,
    ) {
        val existing = sql.createQuery(UserInstallBinding::class) {
            where(table.appId eq appId)
            where(table.userId eq userId)
            where(table.installId eq installId)
            select(table)
        }.limit(1).execute().firstOrNull()

        if (existing == null) {
            val now = Instant.now()
            val entity = UserInstallBinding {
                id = UUID.randomUUID()
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
            save(ctx, entity)
        } else {
            val now = Instant.now()
            sql.createUpdate(UserInstallBinding::class) {
                where(table.id eq existing.id)
                set(lastSeenAt, now)
                set(loginCount, existing.loginCount + 1)
                set(updatedAt, now)
                set(clientIp, clientIp)
                set(clientPlatform, clientPlatform)
            }.execute()
        }
    }
}
