package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOps
import com.ifmix.api.core.jooq.tables.CoreUserInstallBinding.Companion.CORE_USER_INSTALL_BINDING
import com.ifmix.api.core.entity.auth.UserInstallBinding
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * UserInstallBinding jOOQ repository.
 */
@Repository
class UserInstallBindingRepository(
    private val crud: CrudRepoOps,
) {

    /**
     * 记录一次登录绑定（幂等 upsert）：
     * - 不存在 → 插入，loginCount=1
     * - 已存在 → 更新 lastSeenAt、loginCount++、clientIp/platform
     */
    fun recordBinding(
        ctx: SvcCtx,
        appId: UUID,
        userId: UUID,
        installId: UUID,
        clientIp: String?,
        clientPlatform: String?,
    ) {
        val now = Instant.now()
        val existing = ctx.dsl.selectFrom(CORE_USER_INSTALL_BINDING)
            .where(CORE_USER_INSTALL_BINDING.APP_ID.eq(appId))
            .and(CORE_USER_INSTALL_BINDING.USER_ID.eq(userId))
            .and(CORE_USER_INSTALL_BINDING.INSTALL_ID.eq(installId))
            .fetchOne()

        if (existing == null) {
            val entity = UserInstallBinding(
                id = UUID.randomUUID(),
                appId = appId,
                userId = userId,
                installId = installId,
                firstSeenAt = now,
                lastSeenAt = now,
                loginCount = 1,
                clientIp = clientIp,
                clientPlatform = clientPlatform,
                createdAt = now,
                updatedAt = now,
            )
            crud.insert(ctx, CORE_USER_INSTALL_BINDING, entity)
        } else {
            ctx.dsl.update(CORE_USER_INSTALL_BINDING)
                .set(CORE_USER_INSTALL_BINDING.LAST_SEEN_AT, now)
                .set(CORE_USER_INSTALL_BINDING.LOGIN_COUNT, CORE_USER_INSTALL_BINDING.LOGIN_COUNT.add(1))
                .set(CORE_USER_INSTALL_BINDING.UPDATED_AT, now)
                .set(CORE_USER_INSTALL_BINDING.CLIENT_IP, clientIp)
                .set(CORE_USER_INSTALL_BINDING.CLIENT_PLATFORM, clientPlatform)
                .where(CORE_USER_INSTALL_BINDING.APP_ID.eq(appId))
                .and(CORE_USER_INSTALL_BINDING.USER_ID.eq(userId))
                .and(CORE_USER_INSTALL_BINDING.INSTALL_ID.eq(installId))
                .execute()
        }
    }
}
