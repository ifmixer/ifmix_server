package com.ifmix.api.core.service.auth

import com.ifmix.api.core.infra.http.OperationContext
import java.util.UUID

/** 登录成功事件：MergeOnLoginListener 监听，记录 user-install 绑定 + 回填匿名数据归属。 */
data class AuthLoggedInEvent(
    val appId: UUID,
    val authIdentityId: String,
    val appUserId: UUID,
    val installId: UUID?,
    val clientIp: String? = null,
    val clientPlatform: String? = null,
    val ctx: OperationContext,
)
