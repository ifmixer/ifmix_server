package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.service.auth.*
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

/**
 * customer BFF 的认证路由。
 *
 * 公开接口（无需 JWT）：POST /auth/google, /auth/apple, /auth/exchange, /auth/refresh
 * 受保护接口（需 JWT）：POST /auth/logout, PUT /auth/me
 */
@RestController
@RequestMapping("/customer/core", produces = ["application/json"])
class CustomerAuthController(private val authService: AuthService) {

    @Operation(summary = "Google 登录", description = "提交 Google ID Token 换取平台 JWT。免鉴权。idToken 必填。")
    @PostMapping("/mutation/auth/google")
    fun google(ctx: RequestContext, @Valid @RequestBody req: ProviderLoginReq): LoginRes =
        authService.loginWithIdToken(ctx, "google", req)

    @Operation(summary = "Apple 登录", description = "提交 Apple Identity Token 换取平台 JWT。免鉴权。idToken 必填。")
    @PostMapping("/mutation/auth/apple")
    fun apple(ctx: RequestContext, @Valid @RequestBody req: ProviderLoginReq): LoginRes =
        authService.loginWithIdToken(ctx, "apple", req)

    @Operation(summary = "微信登录", description = "提交微信 authorization code 换取平台 JWT。免鉴权。code 必填。失败返回 401001 AUTH_PROVIDER_FAILED。")
    @PostMapping("/mutation/auth/wechat")
    fun wechat(ctx: RequestContext, @Valid @RequestBody req: WechatLoginReq): LoginRes =
        authService.loginWithCode(ctx, "wechat", req)

    @Operation(summary = "同系 App SSO 交换", description = "用 deviceSecret 在同一租户下的兄弟 App 之间免登录切换。免鉴权。")
    @PostMapping("/mutation/auth/exchange")
    fun exchange(ctx: RequestContext, @Valid @RequestBody req: ExchangeReq): ExchangeRes =
        authService.exchange(ctx, req)

    @Operation(summary = "刷新 token", description = "用 refreshToken 换新的 accessToken + refreshToken。免鉴权。token 无效/过期返回 401003。")
    @PostMapping("/mutation/auth/refresh")
    fun refresh(ctx: RequestContext, @Valid @RequestBody req: RefreshReq): RefreshRes =
        authService.refresh(ctx, req)

    @Operation(summary = "登出", description = "宣告当前 refreshToken 作废。需要 Bearer token。")
    @PostMapping("/mutation/auth/logout")
    fun logout(ctx: RequestContext, @Valid @RequestBody req: LogoutReq): LogoutRes =
        authService.logout(ctx, req)

    @Operation(summary = "获取当前用户信息和权益", description = "返回用户身份 + 订阅状态（tier/active/expiresAt）。需要 Bearer token。")
    @PutMapping("/query/auth/me")
    fun me(ctx: RequestContext): MeRes = authService.me(ctx)   // ctx.userId 为空时 service 抛 UNAUTHORIZED
}
