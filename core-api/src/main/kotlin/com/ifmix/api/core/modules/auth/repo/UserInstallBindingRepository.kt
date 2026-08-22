package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.UserInstallBinding
import com.ifmix.api.core.entity.auth.appId
import com.ifmix.api.core.entity.auth.clientIp
import com.ifmix.api.core.entity.auth.clientPlatform
import com.ifmix.api.core.entity.auth.id
import com.ifmix.api.core.entity.auth.installId
import com.ifmix.api.core.entity.auth.lastSeenAt
import com.ifmix.api.core.entity.auth.loginCount
import com.ifmix.api.core.entity.auth.updatedAt
import com.ifmix.api.core.entity.auth.userId
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.AppCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class UserInstallBindingRepository {
    companion object { private val tpl = AppCrudRepoTemplate(UserInstallBinding::class) }

    fun recordBinding(
        mc: ModuleCtx,
        appId: UUID,
        userId: UUID,
        installId: UUID,
        clientIp: String?,
        clientPlatform: String?,
    ): Int {
        val existing = mc.sql.createQuery(UserInstallBinding::class) {
            where(table.get<UUID>("appId") eq appId)
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
            return mc.sql.entities.save(entity).totalAffectedRowCount
        } else {
            val now = Instant.now()
            return mc.sql.createUpdate(UserInstallBinding::class) {
                where(table.id eq existing.id)
                set(table.lastSeenAt, now)
                set(table.loginCount, existing.loginCount + 1)
                set(table.updatedAt, now)
                set(table.clientIp, clientIp)
                set(table.clientPlatform, clientPlatform)
            }.execute()
        }
    }

    fun save(mc: ModuleCtx, entity: UserInstallBinding) = tpl.save(mc, entity)
    fun findById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.findById(mc, appId, id)
    fun deleteById(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.deleteById(mc, appId, id)
    fun exists(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.exists(mc, appId, id)
}
