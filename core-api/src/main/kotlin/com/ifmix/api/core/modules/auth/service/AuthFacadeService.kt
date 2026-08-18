package com.ifmix.api.core.modules.auth.service

import com.ifmix.api.core.infra.db.SvcCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.modules.auth.service.internal.AuthInternalService
import com.ifmix.api.core.modules.auth.service.internal.DeleteAccountRes
import com.ifmix.api.core.modules.auth.service.internal.ExchangeReq
import com.ifmix.api.core.modules.auth.service.internal.ExchangeRes
import com.ifmix.api.core.modules.auth.service.internal.LoginRes
import com.ifmix.api.core.modules.auth.service.internal.LogoutReq
import com.ifmix.api.core.modules.auth.service.internal.LogoutRes
import com.ifmix.api.core.modules.auth.service.internal.MeRes
import com.ifmix.api.core.modules.auth.service.internal.ProviderLoginReq
import com.ifmix.api.core.modules.auth.service.internal.RefreshReq
import com.ifmix.api.core.modules.auth.service.internal.RefreshRes
import com.ifmix.api.core.modules.auth.service.internal.WechatLoginReq
import org.springframework.stereotype.Service

@Service
class AuthFacadeService(
    private val svcCtxFactory: SvcCtxFactory,
    private val internalService: AuthInternalService,
    private val tx: TxRunner,
) {
    fun me(ctx: OperationContext): MeRes = internalService.me(svcCtxFactory.forApp(ctx))
    fun loginWithIdToken(ctx: OperationContext, provider: String, req: ProviderLoginReq): LoginRes =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> internalService.loginWithIdToken(sc, provider, req) }
    fun loginWithCode(ctx: OperationContext, provider: String, req: WechatLoginReq): LoginRes =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> internalService.loginWithCode(sc, provider, req) }
    fun exchange(ctx: OperationContext, req: ExchangeReq): ExchangeRes =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> internalService.exchange(sc, req) }
    fun refresh(ctx: OperationContext, req: RefreshReq): RefreshRes =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> internalService.refresh(sc, req) }
    fun logout(ctx: OperationContext, req: LogoutReq): LogoutRes =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> internalService.logout(sc, req) }
    fun anonymousLogin(ctx: OperationContext): LoginRes =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> internalService.anonymousLogin(sc) }
    fun requestAccountDeletion(ctx: OperationContext): DeleteAccountRes =
        internalService.requestAccountDeletion(svcCtxFactory.forApp(ctx))
}
