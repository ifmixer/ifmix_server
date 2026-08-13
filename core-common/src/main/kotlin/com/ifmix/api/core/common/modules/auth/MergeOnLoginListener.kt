package com.ifmix.api.core.common.modules.auth

import com.ifmix.api.core.common.modules.auth.repo.UserInstallBindingRepository
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

/**
 * 登录后处理：
 * 1. 记录 user-install 绑定关系（用于后续分析）
 * 2. 归并匿名数据（TODO）
 */
@Component
class MergeOnLoginListener(
    private val bindingRepo: UserInstallBindingRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @EventListener
    fun onLogin(e: AuthLoggedInEvent) {
        try {
            // 记录 user-install 绑定
            if (e.installId != null) {
                bindingRepo.recordBinding(
                    ctx = e.ctx.repoCtx,
                    appId = e.appId,
                    userId = e.appUserId,
                    installId = e.installId,
                    clientIp = e.clientIp,
                    clientPlatform = e.clientPlatform,
                )
                log.debug("Recorded user-install binding: user={}, install={}", e.appUserId, e.installId)
            }
        } catch (ex: Exception) {
            log.warn("Failed to record user-install binding: {}", ex.message)
        }

        // TODO: 待实现匿名数据归并（把 installId 下的匿名 scan/collection 归属到 userId）
    }
}
