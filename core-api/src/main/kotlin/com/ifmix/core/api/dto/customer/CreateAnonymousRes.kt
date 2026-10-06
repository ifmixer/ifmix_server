package com.ifmix.core.api.dto.customer

import com.ifmix.core.api.modules.customer.CustomerFacade
import java.util.UUID

/**
 * 匿名 Customer 创建响应（GraphQL `type CreateAnonymousResult` 字段级一致；命名与业务层
 * `com.ifmix.core.api.modules.auth.handler.CreateAnonymousRes` 区分，本类是协议出参）。
 *
 * GraphQL 侧 customer 为 nested resolver 按需查（[CustomerFacade.findById]）；HTTP 无字段裁剪，
 * 固定返回完整 customer 视图。
 */
data class CreateAnonymousRes(
    /** 新建的 customer id（无需查库即可返回）。 */
    val customerId: UUID,
    val customer: CustomerRes,
    val accessToken: String,
    val refreshToken: String,
    /** refresh token 过期时间（ISO-8601）。服务端暂不校验过期。 */
    val refreshExpiresAt: String,
    /** access token 有效期（秒）。 */
    val expiresIn: Int,
)
