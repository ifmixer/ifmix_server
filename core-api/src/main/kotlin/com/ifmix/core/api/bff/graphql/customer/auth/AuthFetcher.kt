package com.ifmix.core.api.bff.graphql.customer.auth

import com.ifmix.core.api.dto.common.ActionResult
import com.ifmix.core.api.generated.types.*
import com.ifmix.core.api.infra.graphql.ActionContextProvider
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.auth.AuthFacade
import com.ifmix.core.api.modules.auth.handler.LoginReq
import com.ifmix.core.api.modules.auth.handler.LoginRes
import com.ifmix.core.api.modules.auth.handler.LogoutReq
import com.ifmix.core.api.modules.auth.handler.RefreshReq
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import java.time.Instant

@DgsComponent
class AuthFetcher(
    private val authService: AuthFacade,
    private val globalTx: GlobalTxRunner,
    private val ctxProvider: ActionContextProvider,
) {

    @DgsQuery(field = "q_auth_me")
    fun me(dfe: DgsDataFetchingEnvironment): MeResult {
        val ctx = ctxProvider.fromDfe(dfe)
        val res = authService.me(ctx)
        return MeResult(
            user = UserInfo(id = res.id, email = res.email),
            tier = res.tier,
            tierActive = res.active,
            tierExpiresAt = res.expiresAt?.let { Instant.ofEpochMilli(it) },
        )
    }

    @DgsMutation(field = "m_auth_login")
    fun login(dfe: DgsDataFetchingEnvironment, @InputArgument input: IdpLoginInput): LoginResult {
        // login 不要求 customer actor；允许两类上下文：当前 customer token（保留 promote/merge）或 installToken（无现有 session）。
        // 两者都必须携带可信 iid；manager/无 token/无 iid 拒绝（在进入事务前）。
        val ctx = ctxProvider.fromDfe(dfe, requireActorType = null)
        ctx.mustGetLoginInstallId()
        val res = globalTx.withTx(ctx) { txCtx ->
            authService.login(txCtx, LoginReq(idpId = input.idpId, credential = input.credential))
        }
        return res.toResult()
    }

    @DgsMutation(field = "m_auth_refreshToken")
    fun refresh(dfe: DgsDataFetchingEnvironment, @InputArgument input: RefreshInput): RefreshResult {
        // refresh 凭证是 body 的 refreshToken；Authorization 携带 accessToken 提供可信 iid，
        // 可能是 customerToken（type=10，含 actor）或 installToken（type=5，无 actor），两者都支持。
        // 只要求 iid 有效；缺失/无效 → UNAUTHORIZED（在进入事务前）。
        val ctx = ctxProvider.fromDfe(dfe, requireActorType = null)
        ctx.mustGetTokenInstallId()
        val res = globalTx.withTx(ctx) { txCtx ->
            authService.refresh(txCtx, RefreshReq(refreshToken = input.refreshToken))
        }
        return RefreshResult(
            accessToken = res.accessToken,
            refreshToken = res.refreshToken,
            expiresIn = res.expiresIn.toInt(),
        )
    }

    @DgsMutation(field = "m_auth_logout")
    fun logout(dfe: DgsDataFetchingEnvironment, @InputArgument input: LogoutInput): ActionResult {
        val ctx = ctxProvider.fromDfe(dfe)
        globalTx.withTx(ctx) { txCtx ->
            authService.logout(txCtx, LogoutReq(refreshToken = input.refreshToken))
        }
        return ActionResult(success = true)
    }

    @DgsMutation(field = "m_auth_deleteAccount")
    fun deleteAccount(dfe: DgsDataFetchingEnvironment): ActionResult {
        val ctx = ctxProvider.fromDfe(dfe)
        // 软删 customer + 解绑 install + 吊销 token 须在同一事务内原子生效
        globalTx.withTx(ctx) { txCtx ->
            authService.requestAccountDeletion(txCtx)
        }
        return ActionResult(success = true)
    }

    private fun LoginRes.toResult() = LoginResult(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresIn = expiresIn.toInt(),
        user = UserInfo(id = user.id, email = user.email),
    )
}
