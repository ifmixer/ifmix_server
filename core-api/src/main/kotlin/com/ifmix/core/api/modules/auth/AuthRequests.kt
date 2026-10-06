package com.ifmix.core.api.modules.auth

import java.util.UUID

/** auth 模块 Facade 层请求记录（controller 从 dto/auth 的 wire input 映射而来；与 handler 实现解耦）。 */

data class LoginReq(
    val idpId: UUID,
    val credential: String,
)

data class RefreshReq(val refreshToken: String)

data class LogoutReq(val refreshToken: String)
