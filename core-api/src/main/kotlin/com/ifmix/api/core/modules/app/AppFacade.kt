package com.ifmix.api.core.modules.app
import org.springframework.stereotype.Service

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.app.handler.AppConfigHandler

/**
 * App 门面——编排 app 模块的跨服务调用。
 *
 * AppConfigHandler 封装了版本管理的复杂业务逻辑（事务、缓存失效），
 * 本类保留为后续扩展点（如 app 注册、slug 管理等）。
 */
@Service
class AppFacade(
    private val handler: AppConfigHandler,
) {
    fun getConfig(appId: String): AppConfigView? = handler.getByAppId(appId)

    fun getActiveConfig(appId: String): AppConfigView {
        return handler.getByAppId(appId)
            ?: throw IllegalStateException("No active AppConfig found for appId: $appId")
    }

    fun getCurrentDoc(appId: String) = handler.getCurrentDoc(appId)
}
