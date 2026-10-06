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
import com.ifmix.core.api.infra.http.ActorRequirement
import com.ifmix.core.api.infra.http.ApiRequestBody
import com.ifmix.core.api.infra.http.Envelope
import com.ifmix.core.api.infra.http.NoInput
import com.ifmix.core.api.infra.http.requireInput
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.auth.AuthFacade
import com.ifmix.core.api.modules.auth.LoginReq
import com.ifmix.core.api.modules.auth.LogoutReq
import com.ifmix.core.api.modules.auth.RefreshReq
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

    companion object {
        // auth 模块 action 常量（原 AuthSpecs 机械搬移；唯一依据 = AuthFetcher 各 action 的 fromDfe 实参）：
        // - REQNAME_LOGIN/REQNAME_REFRESH：`fromDfe(dfe, requireActorType = null)` → NONE（token 可选自验；token 要求由
        //   `mustGetLoginInstallId` / `mustGetTokenInstallId` 精确执行——refresh 的 access token 允许过期/缺失）。
        // - REQNAME_LOGOUT/REQNAME_ME/REQNAME_DELETE_ACCOUNT：`fromDfe(dfe)` 全默认 → CUSTOMER。
        const val REQNAME_LOGIN = "m_auth_session_login"
        const val REQNAME_REFRESH = "m_auth_session_refresh"
        const val REQNAME_LOGOUT = "m_auth_session_logout"
        const val REQNAME_ME = "q_auth_session_me"
        const val REQNAME_DELETE_ACCOUNT = "m_auth_account_deleteOne"
    }

    @Operation(operationId = REQNAME_ME)
    @PostMapping(REQNAME_ME, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun me(request: HttpServletRequest, @RequestBody body: ApiRequestBody<NoInput>): ResponseEntity<Envelope<MeRes>> {
        val ctx = ctxFactory.fromRpc(request, REQNAME_ME, isMutation = false, body = body)
        val res = authService.me(ctx)
        val out = MeRes(
            user = UserInfoRes(id = res.id, email = res.email),
            tier = res.tier,
            tierActive = res.active,
            tierExpiresAt = res.expiresAt?.let { Instant.ofEpochMilli(it) },
        )
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, out))
    }

    @Operation(operationId = REQNAME_LOGIN)
    @PostMapping(REQNAME_LOGIN, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun login(request: HttpServletRequest, @RequestBody body: ApiRequestBody<LoginInput>): ResponseEntity<Envelope<LoginRes>> {
        val ctx = ctxFactory.fromRpc(request, REQNAME_LOGIN, isMutation = true, body = body, requireActorType = ActorRequirement.NONE)
        // login 不要求 customer actor；允许两类上下文：当前 customer token（保留 promote/merge）或 installToken（无现有 session）。
        // 两者都必须携带可信 iid；manager/无 token/无 iid 拒绝（在进入事务前）。
        ctx.mustGetLoginInstallId()
        val input = body.requireInput()
        val res = globalTx.withTx(ctx) { txCtx ->
            authService.login(txCtx, LoginReq(idpId = input.idpId, credential = input.credential))
        }
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, res.toWire()))
    }

    @Operation(operationId = REQNAME_REFRESH)
    @PostMapping(REQNAME_REFRESH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun refresh(request: HttpServletRequest, @RequestBody body: ApiRequestBody<RefreshInput>): ResponseEntity<Envelope<RefreshRes>> {
        val ctx = ctxFactory.fromRpc(request, REQNAME_REFRESH, isMutation = true, body = body, requireActorType = ActorRequirement.NONE)
        // refresh 凭证是 input 的 refreshToken；meta.accessToken 提供可信 iid，
        // 可能是 customerToken（type=10，含 actor）或 installToken（type=5，无 actor），两者都支持。
        // 只要求 iid 有效；缺失/无效 → UNAUTHORIZED（在进入事务前）。
        ctx.mustGetTokenInstallId()
        val input = body.requireInput()
        val res = globalTx.withTx(ctx) { txCtx ->
            authService.refresh(txCtx, RefreshReq(refreshToken = input.refreshToken))
        }
        return ResponseEntity.ok(
            Envelope.ok(ctx.requestId, RefreshRes(accessToken = res.accessToken, refreshToken = res.refreshToken, expiresIn = res.expiresIn.toInt())),
        )
    }

    @Operation(operationId = REQNAME_LOGOUT)
    @PostMapping(REQNAME_LOGOUT, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun logout(request: HttpServletRequest, @RequestBody body: ApiRequestBody<LogoutInput>): ResponseEntity<Envelope<ActionResult>> {
        val ctx = ctxFactory.fromRpc(request, REQNAME_LOGOUT, isMutation = true, body = body)
        val input = body.requireInput()
        globalTx.withTx(ctx) { txCtx ->
            authService.logout(txCtx, LogoutReq(refreshToken = input.refreshToken))
        }
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, ActionResult(success = true)))
    }

    @Operation(operationId = REQNAME_DELETE_ACCOUNT)
    @PostMapping(REQNAME_DELETE_ACCOUNT, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deleteAccount(request: HttpServletRequest, @RequestBody body: ApiRequestBody<NoInput>): ResponseEntity<Envelope<ActionResult>> {
        val ctx = ctxFactory.fromRpc(request, REQNAME_DELETE_ACCOUNT, isMutation = true, body = body)
        // 软删 customer + 解绑 install + 吊销 token 须在同一事务内原子生效
        globalTx.withTx(ctx) { txCtx ->
            authService.requestAccountDeletion(txCtx)
        }
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, ActionResult(success = true)))
    }

    private fun com.ifmix.core.api.modules.auth.handler.LoginRes.toWire() = LoginRes(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresIn = expiresIn.toInt(),
        user = UserInfoRes(id = user.id, email = user.email),
    )
}
