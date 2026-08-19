package com.ifmix.api.core.modules.app.handler

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.app.AppConfigMapper
import com.ifmix.api.core.modules.app.AppConfigPatch
import com.ifmix.api.core.modules.app.AppConfigView
import com.ifmix.api.core.modules.app.repo.AppConfigRepo

/**
 * AppConfig 业务逻辑层。封装版本管理、缓存失效等业务规则。
 */
class AppConfigHandler(
    private val repo: AppConfigRepo,
) {
    fun getByAppId(appId: String): AppConfigView? = repo.getByAppId(appId)

    fun getByAppleBundleId(bundleId: String): AppConfigView? = repo.getByAppleBundleId(bundleId)

    fun getByAndroidPackage(pkg: String): AppConfigView? = repo.getByAndroidPackage(pkg)

    fun getCurrentDoc(appId: String) = repo.getCurrentDoc(appId)

    /** 追加新版本：事务内软删当前版本 + 插入 revision+1 的新当前版本。 */
    fun newVersion(ctx: RequestContext, appId: String, patch: AppConfigPatch) {
        repo.newVersion(ctx, appId, patch)
    }

    /** 切换指定 revision 的启用状态，返回切换后当前生效的配置。 */
    fun toggleRevision(ctx: RequestContext, id: String, enabled: Boolean): AppConfigView? {
        return repo.toggleRevision(ctx, id, enabled)
    }
}
