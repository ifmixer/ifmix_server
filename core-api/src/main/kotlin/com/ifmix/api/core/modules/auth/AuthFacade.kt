package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.infra.db.SvcCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.tx.TxRunner
import com.ifmix.api.core.modules.auth.handler.AuthHandler
import com.ifmix.api.core.modules.auth.handler.DeleteAccountRes
import com.ifmix.api.core.modules.auth.handler.ExchangeReq
import com.ifmix.api.core.modules.auth.handler.ExchangeRes
import com.ifmix.api.core.modules.auth.handler.LoginRes
import com.ifmix.api.core.modules.auth.handler.LogoutReq
import com.ifmix.api.core.modules.auth.handler.LogoutRes
import com.ifmix.api.core.modules.auth.handler.MeRes
import com.ifmix.api.core.modules.auth.handler.ProviderLoginReq
import com.ifmix.api.core.modules.auth.handler.RefreshReq
import com.ifmix.api.core.modules.auth.handler.RefreshRes
import com.ifmix.api.core.modules.auth.handler.WechatLoginReq
import org.springframework.stereotype.Service

@Service
class AuthFacade(
    private val svcCtxFactory: SvcCtxFactory,
    private val handler: AuthHandler,
    private val tx: TxRunner,
) {
    fun me(ctx: OperationContext): MeRes = handler.me(svcCtxFactory.forApp(ctx))
    fun loginWithIdToken(ctx: OperationContext, provider: String, req: ProviderLoginReq): LoginRes =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> handler.loginWithIdToken(sc, provider, req) }
    fun loginWithCode(ctx: OperationContext, provider: String, req: WechatLoginReq): LoginRes =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> handler.loginWithCode(sc, provider, req) }
    fun exchange(ctx: OperationContext, req: ExchangeReq): ExchangeRes =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> handler.exchange(sc, req) }
    fun refresh(ctx: OperationContext, req: RefreshReq): RefreshRes =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> handler.refresh(sc, req) }
    fun logout(ctx: OperationContext, req: LogoutReq): LogoutRes =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> handler.logout(sc, req) }
    fun anonymousLogin(ctx: OperationContext): LoginRes =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> handler.anonymousLogin(sc) }
    fun requestAccountDeletion(ctx: OperationContext): DeleteAccountRes =
        handler.requestAccountDeletion(svcCtxFactory.forApp(ctx))
}
