package com.ifmix.core.api.bff.graphql.customer.auth

import com.ifmix.core.api.dto.common.OperationResult
import com.ifmix.core.api.generated.types.*
import com.ifmix.core.api.infra.graphql.OperationContextProvider
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
import java.util.UUID

@DgsComponent
class AuthFetcher(
    private val authService: AuthFacade,
    private val globalTx: GlobalTxRunner,
    private val ctxProvider: OperationContextProvider,
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
        val ctx = ctxProvider.fromDfe(dfe)
        val res = globalTx.withTx(ctx) { txCtx ->
            authService.login(txCtx, LoginReq(idpId = input.idpId, credential = input.credential))
        }
        return res.toResult()
    }

    @DgsMutation(field = "m_auth_refreshToken")
    fun refresh(dfe: DgsDataFetchingEnvironment, @InputArgument input: RefreshInput): RefreshResult {
        val ctx = ctxProvider.fromDfe(dfe)
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
    fun logout(dfe: DgsDataFetchingEnvironment, @InputArgument input: LogoutInput): OperationResult {
        val ctx = ctxProvider.fromDfe(dfe)
        globalTx.withTx(ctx) { txCtx ->
            authService.logout(txCtx, LogoutReq(refreshToken = input.refreshToken))
        }
        return OperationResult(success = true)
    }

    @DgsMutation(field = "m_auth_deleteAccount")
    fun deleteAccount(dfe: DgsDataFetchingEnvironment): OperationResult {
        val ctx = ctxProvider.fromDfe(dfe)
        authService.requestAccountDeletion(ctx)
        return OperationResult(success = true)
    }

    private fun LoginRes.toResult() = LoginResult(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresIn = expiresIn.toInt(),
        user = UserInfo(id = user.id, email = user.email),
    )
}
