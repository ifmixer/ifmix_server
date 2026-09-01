package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.infra.db.ModuleCtxFactory
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

/**
 * 登录后处理：归并匿名数据（阶段 4 实现）。
 * install 绑定记录已随 install 主体删除。
 */
@Component
class MergeOnLoginListener(
    private val mcFactory: ModuleCtxFactory,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @EventListener
    fun onLogin(e: AuthLoggedInEvent) {
        // TODO(阶段4): 匿名 customer 数据归并（把匿名 customerId 下的 scan/collection 归属到登录 customerId）
        log.debug("Login event: app={}, customer={}", e.appId, e.customerId)
    }
}
