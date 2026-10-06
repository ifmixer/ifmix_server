package com.ifmix.core.api.bff.api.customer.auth

import com.ifmix.core.api.bff.api.customer.demo.DemoController
import com.ifmix.core.api.dto.auth.customer.CreateAnonymousRes
import com.ifmix.core.api.dto.auth.customer.CustomerRes
import com.ifmix.core.api.infra.http.ActionContextFactory
import com.ifmix.core.api.infra.http.ActorRequirement
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ApiRequestBody
import com.ifmix.core.api.infra.http.Envelope
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.NoInput
import com.ifmix.core.api.infra.ratelimit.RateLimitProperties
import com.ifmix.core.api.infra.ratelimit.RateLimiter
import com.ifmix.core.api.infra.ratelimit.RateLimitResult
import com.ifmix.core.api.infra.ratelimit.Window
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.auth.AuthFacade
import com.ifmix.core.api.modules.auth.customer.CustomerFacade
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * customer 模块 API controller：1 个 `POST /api/customer/core/{actionName}`。
 *
 * 逻辑逐行对照原 CustomerFetcher.createAnonymousCustomer（迁移不改行为）：
 * - 无需登录（带 token 照校验），但必须携带有效可信 iid（[com.ifmix.core.api.infra.http.ActionContext.mustGetTokenInstallId]，
 *   iid 无效/缺失 → UNAUTHORIZED，进入事务前）；
 * - 下游 install 层限流（attest 规格 §4.6）：install 层 → IP 层 → 业务；install 层拒绝不碰 IP 计数器；
 *   IP 层拒绝 install 额度不退；无可信 token iid（legacy fallback 期）→ legacy IP 层独立计数器
 *   （RPC 路径 legacyInstallId 恒为 null，mustGetTokenInstallId 已先行拦截，此分支仅保留对齐）；
 * - 限流拒绝 429000 且必带 retryAfterSec（GlobalExceptionHandler 自动写 Retry-After 头）；
 * - mutation 包 [GlobalTxRunner.withTx]；customer 视图按原 nested resolver 语义查库补全
 *   （原 GraphQL 仅在客户端选取 customer 字段时查库，HTTP 无字段裁剪故固定返回）。
 */
@RestController
@RequestMapping("/api/customer/core", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Customer API", description = "customer 模块 API（GraphQL 去化 M1）")
class CustomerController(
    private val ctxFactory: ActionContextFactory,
    private val authService: AuthFacade,
    private val customerFacade: CustomerFacade,
    private val globalTx: GlobalTxRunner,
    private val rateLimiter: RateLimiter,
    private val rlProps: RateLimitProperties,
) {

    companion object {
        // customer 模块 action 常量（原 CustomerSpecs 机械搬移）：原 CustomerFetcher
        // `fromDfe(dfe, requireActorType = null)` → INSTALL_OR_CUSTOMER（不要求登录、token 照校验、
        // install/customer token 皆可；createAnonymous 只要求可信 iid）。
        const val CREATE_ANONYMOUS = "m_auth_customer_createAnonymous"
    }

    @Operation(operationId = CREATE_ANONYMOUS)
    @PostMapping(CREATE_ANONYMOUS, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createAnonymousCustomer(
        request: HttpServletRequest,
        @RequestBody body: ApiRequestBody<NoInput>,
    ): ResponseEntity<Envelope<CreateAnonymousRes>> {
        val ctx = ctxFactory.fromRpc(
            request, CREATE_ANONYMOUS, isMutation = true, body = body,
            requireActorType = ActorRequirement.INSTALL_OR_CUSTOMER,
        )
        // 只要求携带有效可信 iid（token 类型不限）；legacy fallback 关闭后无 token iid → 这里即 401000
        ctx.mustGetTokenInstallId()
        val projectId = ctx.mustGetProjectId()
        val clientIp = ctx.clientIp ?: "unknown"
        val iid = ctx.tokenInstallId
        if (iid != null) {
            when (val rl = rateLimiter.check(
                Window.UTC_DAY,
                "ratelimit:$projectId:anonymous:install:day:$iid",
                rlProps.anonymous.installDay,
            )) {
                RateLimitResult.Allowed, RateLimitResult.Degraded -> Unit
                is RateLimitResult.Limited ->
                    throw ApiError(ErrorCode.RATE_LIMITED, "too many anonymous customer creations", retryAfterSec = rl.retryAfterSec)
            }
            when (val rl = rateLimiter.check(
                Window.MINUTE,
                "ratelimit:$projectId:anonymous:ip:min:$clientIp",
                rlProps.anonymous.ipMinute,
            )) {
                RateLimitResult.Allowed, RateLimitResult.Degraded -> Unit
                is RateLimitResult.Limited ->
                    throw ApiError(ErrorCode.RATE_LIMITED, "too many anonymous customer creations", retryAfterSec = rl.retryAfterSec)
            }
            when (val rl = rateLimiter.check(
                Window.UTC_DAY,
                "ratelimit:$projectId:anonymous:ip:day:$clientIp",
                rlProps.anonymous.ipDay,
            )) {
                RateLimitResult.Allowed, RateLimitResult.Degraded -> Unit
                is RateLimitResult.Limited ->
                    throw ApiError(ErrorCode.RATE_LIMITED, "too many anonymous customer creations", retryAfterSec = rl.retryAfterSec)
            }
        } else {
            // legacy 严格阈值独立计数器（key 带 legacy: 段）；RPC 路径不可达（见类注释），保留对齐原 fetcher
            when (val rl = rateLimiter.check(
                Window.MINUTE,
                "ratelimit:$projectId:anonymous:legacy:ip:min:$clientIp",
                rlProps.anonymous.legacyIpMinute,
            )) {
                RateLimitResult.Allowed, RateLimitResult.Degraded -> Unit
                is RateLimitResult.Limited ->
                    throw ApiError(ErrorCode.RATE_LIMITED, "too many anonymous customer creations", retryAfterSec = rl.retryAfterSec)
            }
        }
        val res = globalTx.withTx(ctx) { txCtx -> authService.createAnonymousCustomer(txCtx) }
        // customer 视图：原 nested resolver 语义（按 customerId 查库，未命中 NOT_FOUND）
        val customer = customerFacade.findById(ctx, res.customerId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "customer not found")
        return ResponseEntity.ok(
            Envelope.ok(ctx.requestId, 
                CreateAnonymousRes(
                    customerId = res.customerId,
                    customer = CustomerRes(id = customer.id, anonymous = customer.anonymous),
                    accessToken = res.accessToken,
                    refreshToken = res.refreshToken,
                    refreshExpiresAt = res.refreshExpiresAt.toString(),
                    expiresIn = res.expiresIn.toInt(),
                )),
        )
    }
}
