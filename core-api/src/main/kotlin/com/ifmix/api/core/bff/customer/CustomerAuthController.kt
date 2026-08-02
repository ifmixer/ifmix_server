package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.service.auth.*
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

/**
 * customer BFF 的认证路由。
 *
 * 公开接口（无需 JWT）：POST /auth/google, /auth/apple, /auth/wechat, /auth/exchange, /auth/refresh, /auth/anonymous
 * 受保护接口（需 JWT）：POST /auth/logout, /auth/deleteAccount, PUT /auth/me
 */
@RestController
@RequestMapping("/customer/core")
class CustomerAuthController(private val authService: AuthService) {

    @Operation(
        summary = "Google 登录",
        description = """提交 Google ID Token 换取平台 JWT。免鉴权。idToken 必填。
登录成功后自动将当前 x-install-id 下的匿名扫描记录迁移到登录用户名下（同步完成，响应返回时数据已迁移）。
仅迁移从未绑定过用户的匿名记录，不会抢走其他账号的数据。
登出后调 auth/anonymous 回到匿名态，已绑定给 userId 的记录在匿名态下不可见。""",
    )
    @PostMapping("/mutation/auth/google")
    fun google(ctx: OperationContext, @Valid @RequestBody req: ProviderLoginReq): LoginRes =
        authService.loginWithIdToken(ctx, "google", req)

    @Operation(
        summary = "Apple 登录",
        description = """提交 Apple Identity Token 换取平台 JWT。免鉴权。idToken 必填。
登录成功后自动将当前 x-install-id 下的匿名扫描记录迁移到登录用户名下（同步完成，响应返回时数据已迁移）。
仅迁移从未绑定过用户的匿名记录，不会抢走其他账号的数据。
登出后调 auth/anonymous 回到匿名态，已绑定给 userId 的记录在匿名态下不可见。""",
    )
    @PostMapping("/mutation/auth/apple")
    fun apple(ctx: OperationContext, @Valid @RequestBody req: ProviderLoginReq): LoginRes =
        authService.loginWithIdToken(ctx, "apple", req)

    @Operation(
        summary = "微信登录",
        description = """提交微信 authorization code 换取平台 JWT。免鉴权。code 必填。失败返回 401001 AUTH_PROVIDER_FAILED。
登录成功后自动将当前 x-install-id 下的匿名扫描记录迁移到登录用户名下（同步完成，响应返回时数据已迁移）。
仅迁移从未绑定过用户的匿名记录，不会抢走其他账号的数据。
登出后调 auth/anonymous 回到匿名态，已绑定给 userId 的记录在匿名态下不可见。""",
    )
    @PostMapping("/mutation/auth/wechat")
    fun wechat(ctx: OperationContext, @Valid @RequestBody req: WechatLoginReq): LoginRes =
        authService.loginWithCode(ctx, "wechat", req)

    @Operation(
        summary = "匿名 token 签发",
        description = """
        未登录用户获取匿名 access token，以支持登录前的扫描流程。
        服务端按 installId 建/取一个匿名用户，返回和正常登录相同结构的 token。
        客户端可统一走 Bearer token 流程，无需「有 token / 没 token」两套分支。
        免鉴权。x-install-id 必填。
        幂等：同一个 installId 反复调用返回同一个匿名用户（同一个 user.id）。
        token 丢失后可重新调用获取新 token，用户数据不会丢失。
        匿名 deviceSecret 不可用于 auth/exchange（SSO 交换仅限已登录用户）。
    """,
    )
    @PostMapping("/mutation/auth/anonymous")
    fun anonymous(ctx: OperationContext): LoginRes =
        authService.anonymousLogin(ctx)

    @Operation(summary = "同系 App SSO 交换", description = "用 deviceSecret 在同一租户下的兄弟 App 之间免登录切换。免鉴权。")
    @PostMapping("/mutation/auth/exchange")
    fun exchange(ctx: OperationContext, @Valid @RequestBody req: ExchangeReq): ExchangeRes =
        authService.exchange(ctx, req)

    @Operation(summary = "刷新 token", description = "用 refreshToken 换新的 accessToken + refreshToken。免鉴权。token 无效/过期返回 401003。")
    @PostMapping("/mutation/auth/refresh")
    fun refresh(ctx: OperationContext, @Valid @RequestBody req: RefreshReq): RefreshRes =
        authService.refresh(ctx, req)

    @Operation(summary = "登出", description = "宣告当前 refreshToken 作废。需要 Bearer token。\n登出后客户端应调 auth/anonymous 重新获取匿名 token，否则后续接口将返回 401。")
    @PostMapping("/mutation/auth/logout")
    fun logout(ctx: OperationContext, @Valid @RequestBody req: LogoutReq): LogoutRes =
        authService.logout(ctx, req)

    @Operation(summary = "获取当前用户信息和权益", description = "返回用户身份 + 订阅状态（tier/active/expiresAt）。需要 Bearer token。")
    @PutMapping("/query/auth/me")
    fun me(ctx: OperationContext): MeRes = authService.me(ctx)   // ctx.userId 为空时 service 抛 UNAUTHORIZED

    @Operation(
        summary = "请求删除账号",
        description = """
        存储一条删除请求，后台异步处理。符合 App Store 审核要求。
        调用后账号不会立即删除，服务端会在 30 天内处理。
        期间用户可重新登录取消删除。
        需要 Bearer token。
        「重新登录取消」是自动的：auth/google 等登录成功即自动撤销删除请求，无需额外接口。
        提交删除请求后当前 token 继续有效直到过期。
    """,
    )
    @PostMapping("/mutation/auth/deleteAccount")
    fun deleteAccount(ctx: OperationContext): DeleteAccountRes =
        authService.requestAccountDeletion(ctx)
}
