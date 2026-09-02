package com.ifmix.core.api.modules.auth

import com.ifmix.core.api.infra.http.OperationContext
import java.util.UUID

/** 登录成功事件：MergeOnLoginListener 监听，回填匿名数据归属。 */
data class AuthLoggedInEvent(
    val appId: UUID,
    val authIdentityId: String,
    val customerId: UUID,
    val clientIp: String? = null,
    val clientPlatform: String? = null,
    val ctx: OperationContext,
)
