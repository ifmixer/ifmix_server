package com.ifmix.api.core.modules.auth.service

import com.ifmix.api.core.infra.http.OperationContext
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AuthFacadeService(
    private val queries: AuthQueries,
    private val commands: AuthCommands,
) {
    fun me(ctx: OperationContext): MeRes = queries.me(ctx)
    fun loginWithIdToken(ctx: OperationContext, provider: String, req: ProviderLoginReq): LoginRes =
        commands.loginWithIdToken(ctx, provider, req)
    fun loginWithCode(ctx: OperationContext, provider: String, req: WechatLoginReq): LoginRes =
        commands.loginWithCode(ctx, provider, req)
    fun exchange(ctx: OperationContext, req: ExchangeReq): ExchangeRes = commands.exchange(ctx, req)
    fun refresh(ctx: OperationContext, req: RefreshReq): RefreshRes = commands.refresh(ctx, req)
    fun logout(ctx: OperationContext, req: LogoutReq): LogoutRes = commands.logout(ctx, req)
    fun anonymousLogin(ctx: OperationContext): LoginRes = commands.anonymousLogin(ctx)
    fun requestAccountDeletion(ctx: OperationContext): DeleteAccountRes = commands.requestAccountDeletion(ctx)
}
