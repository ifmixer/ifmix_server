package com.ifmix.api.core.modules.auth.service

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import org.springframework.stereotype.Service

@Service
class AuthFacadeService(
    private val internalService: AuthInternalService,
    private val tx: TxRunner,
) {
    private fun svc(ctx: OperationContext) = SvcCtx(op = ctx, dsl = SvcCtx.DEFAULT.dsl)

    fun me(ctx: OperationContext): MeRes = internalService.me(svc(ctx))
    fun loginWithIdToken(ctx: OperationContext, provider: String, req: ProviderLoginReq): LoginRes =
        tx.withTx(svc(ctx)) { sc -> internalService.loginWithIdToken(sc, provider, req) }
    fun loginWithCode(ctx: OperationContext, provider: String, req: WechatLoginReq): LoginRes =
        tx.withTx(svc(ctx)) { sc -> internalService.loginWithCode(sc, provider, req) }
    fun exchange(ctx: OperationContext, req: ExchangeReq): ExchangeRes =
        tx.withTx(svc(ctx)) { sc -> internalService.exchange(sc, req) }
    fun refresh(ctx: OperationContext, req: RefreshReq): RefreshRes =
        tx.withTx(svc(ctx)) { sc -> internalService.refresh(sc, req) }
    fun logout(ctx: OperationContext, req: LogoutReq): LogoutRes =
        tx.withTx(svc(ctx)) { sc -> internalService.logout(sc, req) }
    fun anonymousLogin(ctx: OperationContext): LoginRes =
        tx.withTx(svc(ctx)) { sc -> internalService.anonymousLogin(sc) }
    fun requestAccountDeletion(ctx: OperationContext): DeleteAccountRes =
        internalService.requestAccountDeletion(svc(ctx))
}
