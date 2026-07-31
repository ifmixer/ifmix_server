package com.ifmix.api.core.service.auth

/** 登录成功事件：MergeOnLoginListener 监听，记录 user-install 绑定 + 回填匿名数据归属。 */
data class AuthLoggedInEvent(
    val appId: String,
    val authIdentityId: String,
    val appUserId: String,
    val installId: String?,
    val clientIp: String? = null,
    val clientPlatform: String? = null,
)
