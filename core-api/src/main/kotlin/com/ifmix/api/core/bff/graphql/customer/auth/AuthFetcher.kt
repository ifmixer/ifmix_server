package com.ifmix.api.core.bff.graphql.customer.auth

import com.ifmix.api.core.dto.common.OperationResult
import com.ifmix.api.core.generated.types.*
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.modules.auth.AuthFacade
import com.ifmix.api.core.modules.auth.handler.ExchangeReq
import com.ifmix.api.core.modules.auth.handler.LoginRes
import com.ifmix.api.core.modules.auth.handler.LogoutReq
import com.ifmix.api.core.modules.auth.handler.ProviderLoginReq
import com.ifmix.api.core.modules.auth.handler.RefreshReq
import com.ifmix.api.core.modules.auth.handler.WechatLoginReq
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import java.time.Instant

@DgsComponent
class AuthFetcher(
    private val authService: AuthFacade,
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

    @DgsMutation(field = "m_auth_loginGoogle")
    fun loginGoogle(dfe: DgsDataFetchingEnvironment, @InputArgument input: ProviderLoginInput): LoginResult {
        val ctx = ctxProvider.fromDfe(dfe)
        return authService.loginWithIdToken(ctx, "google", ProviderLoginReq(idToken = input.idToken)).toResult()
    }

    @DgsMutation(field = "m_auth_loginApple")
    fun loginApple(dfe: DgsDataFetchingEnvironment, @InputArgument input: ProviderLoginInput): LoginResult {
        val ctx = ctxProvider.fromDfe(dfe)
        return authService.loginWithIdToken(ctx, "apple", ProviderLoginReq(idToken = input.idToken)).toResult()
    }

    @DgsMutation(field = "m_auth_loginWechat")
    fun loginWechat(dfe: DgsDataFetchingEnvironment, @InputArgument input: WechatLoginInput): LoginResult {
        val ctx = ctxProvider.fromDfe(dfe)
        return authService.loginWithCode(ctx, "wechat", WechatLoginReq(code = input.code)).toResult()
    }

    @DgsMutation(field = "m_auth_loginAnonymous")
    fun loginAnonymous(dfe: DgsDataFetchingEnvironment): LoginResult {
        val ctx = ctxProvider.fromDfe(dfe)
        return authService.anonymousLogin(ctx).toResult()
    }

    @DgsMutation(field = "m_auth_exchangeToken")
    fun exchange(dfe: DgsDataFetchingEnvironment, @InputArgument input: ExchangeInput): ExchangeResult {
        val ctx = ctxProvider.fromDfe(dfe)
        val res = authService.exchange(ctx, ExchangeReq(deviceSecret = input.deviceSecret))
        return ExchangeResult(
            accessToken = res.accessToken,
            refreshToken = res.refreshToken,
            expiresIn = res.expiresIn.toInt(),
        )
    }

    @DgsMutation(field = "m_auth_refreshToken")
    fun refresh(dfe: DgsDataFetchingEnvironment, @InputArgument input: RefreshInput): RefreshResult {
        val ctx = ctxProvider.fromDfe(dfe)
        val res = authService.refresh(ctx, RefreshReq(refreshToken = input.refreshToken))
        return RefreshResult(
            accessToken = res.accessToken,
            refreshToken = res.refreshToken,
            expiresIn = res.expiresIn.toInt(),
        )
    }

    @DgsMutation(field = "m_auth_logout")
    fun logout(dfe: DgsDataFetchingEnvironment, @InputArgument input: LogoutInput): OperationResult {
        val ctx = ctxProvider.fromDfe(dfe)
        authService.logout(ctx, LogoutReq(refreshToken = input.refreshToken))
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
        deviceSecret = deviceSecret,
    )
}
