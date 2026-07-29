package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.service.auth.*
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

/**
 * customer BFF 的认证路由。
 *
 * 公开接口（无需 JWT）：POST /auth/google, /auth/apple, /auth/exchange, /auth/refresh
 * 受保护接口（需 JWT）：POST /auth/logout, PUT /auth/me
 */
@RestController
@RequestMapping("/customer/core")
class CustomerAuthController(private val authService: AuthService) {

    @PostMapping("/mutation/auth/google")
    fun google(ctx: RequestContext, @Valid @RequestBody req: LoginReq): LoginRes =
        authService.loginWithProvider(ctx, "google", req)

    @PostMapping("/mutation/auth/apple")
    fun apple(ctx: RequestContext, @Valid @RequestBody req: LoginReq): LoginRes =
        authService.loginWithProvider(ctx, "apple", req)

    @PostMapping("/mutation/auth/exchange")
    fun exchange(ctx: RequestContext, @Valid @RequestBody req: ExchangeReq): ExchangeRes =
        authService.exchange(ctx, req)

    @PostMapping("/mutation/auth/refresh")
    fun refresh(ctx: RequestContext, @Valid @RequestBody req: RefreshReq): RefreshRes =
        authService.refresh(ctx, req)

    @PostMapping("/mutation/auth/logout")
    fun logout(ctx: RequestContext, @Valid @RequestBody req: LogoutReq): LogoutRes =
        authService.logout(ctx, req)

    @PutMapping("/query/auth/me")
    fun me(ctx: RequestContext): MeRes = authService.me(ctx)   // ctx.userId 为空时 service 抛 UNAUTHORIZED
}
