package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.common.http.ClientPlatform
import com.ifmix.api.core.common.http.RequestContext

/** 登录成功事件：mergeOnLogin 监听，回填匿名 scan/订阅归属，并记录设备绑定。 */
data class AuthLoggedInEvent(
    val appId: String,
    val authIdentityId: String,
    val appUserId: String,
    val installId: String?,
    val clientIp: String? = null,
    val clientPlatform: String? = null,
    val ctx: RequestContext? = null,
)
