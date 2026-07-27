package com.ifmix.api.core.modules.auth

/** 登录成功事件：mergeOnLogin 监听，回填匿名 scan/订阅归属。 */
data class AuthLoggedInEvent(
    val appId: String,
    val authIdentityId: String,
    val appUserId: String,
    val installId: String?,
)
