package com.ifmix.api.core.service.auth

import com.ifmix.api.core.infra.http.RequestContext
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

/**
 * 登录后归并匿名数据（暂未实现，待全量迁移至 PostgreSQL 后重构）。
 * 当前为 no-op，避免编译错误。
 */
@Component
class MergeOnLoginListener() {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @EventListener
    fun onLogin(e: AuthLoggedInEvent) {
        // TODO: 待迁移完成后实现基于 Jimmer Repository 的数据归并逻辑
        log.debug("mergeOnLogin: no-op (migration pending)")
    }
}
