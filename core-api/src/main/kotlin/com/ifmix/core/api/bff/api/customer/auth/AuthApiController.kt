package com.ifmix.core.api.bff.api.customer.auth

import com.ifmix.core.api.dto.auth.LoginInput
import com.ifmix.core.api.dto.auth.LoginRes
import com.ifmix.core.api.dto.auth.LogoutInput
import com.ifmix.core.api.dto.auth.MeRes
import com.ifmix.core.api.dto.auth.RefreshInput
import com.ifmix.core.api.dto.auth.RefreshRes
import com.ifmix.core.api.dto.auth.UserInfoRes
import com.ifmix.core.api.dto.common.ActionResult
import com.ifmix.core.api.infra.http.ActionContextFactory
import com.ifmix.core.api.infra.http.ApiRequestBody
import com.ifmix.core.api.infra.http.Envelope
import com.ifmix.core.api.infra.http.NoInput
import com.ifmix.core.api.infra.http.requireInput
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.auth.AuthFacade
import com.ifmix.core.api.modules.auth.handler.LoginReq
import com.ifmix.core.api.modules.auth.handler.LogoutReq
import com.ifmix.core.api.modules.auth.handler.RefreshReq
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * auth 模块 API controller（rollout M2）。编排自 AuthFetcher 逐行平移：
 * - login：不要求 customer actor；允许 customer token（保留 promote/merge）或 installToken；
 *   两者都必须携带可信 iid（[com.ifmix.core.api.infra.http.ActionContext.mustGetLoginInstallId]）。
 * - refresh：凭证是 input 的 refreshToken；meta.accessToken 提供可信 iid，允许过期/缺失（type=10 或 5 均可）。
 * - logout/me/deleteAccount：customer token。
 */
@RestController
@RequestMapping("/api/customer/core", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Auth API", description = "auth 模块（登录/会话/注销）")
class AuthApiController(
    private val authService: AuthFacade,
    private val globalTx: GlobalTxRunner,
    private val ctxFactory: ActionContextFactory,
) {

    @Operation(operationId = "q_auth_session_me")
    @PostMapping("q_auth_session_me", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun me(request: HttpServletRequest, @RequestBody body: ApiRequestBody<NoInput>): ResponseEntity<Envelope<MeRes>> {
        val ctx = ctxFactory.fromRpc(request, AuthSpecs.ME, body.meta)
        val res = authService.me(ctx)
        val out = MeRes(
            user = UserInfoRes(id = res.id, email = res.email),
            tier = res.tier,
            tierActive = res.active,
            tierExpiresAt = res.expiresAt?.let { Instant.ofEpochMilli(it) },
        )
        return ResponseEntity.ok(Envelope.ok(out).copy(reqId = ctx.requestId))
    }

    @Operation(operationId = "m_auth_session_login")
    @PostMapping("m_auth_session_login", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun login(request: HttpServletRequest, @RequestBody body: ApiRequestBody<LoginInput>): ResponseEntity<Envelope<LoginRes>> {
        val ctx = ctxFactory.fromRpc(request, AuthSpecs.LOGIN, body.meta)
        // login 不要求 customer actor；允许两类上下文：当前 customer token（保留 promote/merge）或 installToken（无现有 session）。
        // 两者都必须携带可信 iid；manager/无 token/无 iid 拒绝（在进入事务前）。
        ctx.mustGetLoginInstallId()
        val input = body.requireInput()
        val res = globalTx.withTx(ctx) { txCtx ->
            authService.login(txCtx, LoginReq(idpId = input.idpId, credential = input.credential))
        }
        return ResponseEntity.ok(Envelope.ok(res.toWire()).copy(reqId = ctx.requestId))
    }

    @Operation(operationId = "m_auth_session_refresh")
    @PostMapping("m_auth_session_refresh", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun refresh(request: HttpServletRequest, @RequestBody body: ApiRequestBody<RefreshInput>): ResponseEntity<Envelope<RefreshRes>> {
        val ctx = ctxFactory.fromRpc(request, AuthSpecs.REFRESH, body.meta)
        // refresh 凭证是 input 的 refreshToken；meta.accessToken 提供可信 iid，
        // 可能是 customerToken（type=10，含 actor）或 installToken（type=5，无 actor），两者都支持。
        // 只要求 iid 有效；缺失/无效 → UNAUTHORIZED（在进入事务前）。
        ctx.mustGetTokenInstallId()
        val input = body.requireInput()
        val res = globalTx.withTx(ctx) { txCtx ->
            authService.refresh(txCtx, RefreshReq(refreshToken = input.refreshToken))
        }
        return ResponseEntity.ok(
            Envelope.ok(RefreshRes(accessToken = res.accessToken, refreshToken = res.refreshToken, expiresIn = res.expiresIn.toInt()))
                .copy(reqId = ctx.requestId),
        )
    }

    @Operation(operationId = "m_auth_session_logout")
    @PostMapping("m_auth_session_logout", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun logout(request: HttpServletRequest, @RequestBody body: ApiRequestBody<LogoutInput>): ResponseEntity<Envelope<ActionResult>> {
        val ctx = ctxFactory.fromRpc(request, AuthSpecs.LOGOUT, body.meta)
        val input = body.requireInput()
        globalTx.withTx(ctx) { txCtx ->
            authService.logout(txCtx, LogoutReq(refreshToken = input.refreshToken))
        }
        return ResponseEntity.ok(Envelope.ok(ActionResult(success = true)).copy(reqId = ctx.requestId))
    }

    @Operation(operationId = "m_auth_account_deleteOne")
    @PostMapping("m_auth_account_deleteOne", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deleteAccount(request: HttpServletRequest, @RequestBody body: ApiRequestBody<NoInput>): ResponseEntity<Envelope<ActionResult>> {
        val ctx = ctxFactory.fromRpc(request, AuthSpecs.DELETE_ACCOUNT, body.meta)
        // 软删 customer + 解绑 install + 吊销 token 须在同一事务内原子生效
        globalTx.withTx(ctx) { txCtx ->
            authService.requestAccountDeletion(txCtx)
        }
        return ResponseEntity.ok(Envelope.ok(ActionResult(success = true)).copy(reqId = ctx.requestId))
    }

    private fun com.ifmix.core.api.modules.auth.handler.LoginRes.toWire() = LoginRes(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresIn = expiresIn.toInt(),
        user = UserInfoRes(id = user.id, email = user.email),
    )
}
