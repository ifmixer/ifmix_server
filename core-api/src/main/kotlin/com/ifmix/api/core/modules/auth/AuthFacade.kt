package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.infra.db.ModuleCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.auth.handler.AuthAggHandler
import com.ifmix.api.core.modules.auth.handler.DeleteAccountRes
import com.ifmix.api.core.modules.auth.handler.LoginReq
import com.ifmix.api.core.modules.auth.handler.LoginRes
import com.ifmix.api.core.modules.auth.handler.LogoutReq
import com.ifmix.api.core.modules.auth.handler.LogoutRes
import com.ifmix.api.core.modules.auth.handler.MeRes
import com.ifmix.api.core.modules.auth.handler.RefreshReq
import com.ifmix.api.core.modules.auth.handler.RefreshRes
import org.springframework.stereotype.Service

@Service
class AuthFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: AuthAggHandler,
) {
    fun me(ctx: OperationContext): MeRes =
        handler.me(mcFactory.forApp(ctx))

    fun login(ctx: OperationContext, req: LoginReq): LoginRes =
        handler.login(mcFactory.forApp(ctx), req)

    fun refresh(ctx: OperationContext, req: RefreshReq): RefreshRes =
        handler.refresh(mcFactory.forApp(ctx), req)

    fun logout(ctx: OperationContext, req: LogoutReq): LogoutRes =
        handler.logout(mcFactory.forApp(ctx), req)

    fun requestAccountDeletion(ctx: OperationContext): DeleteAccountRes =
        handler.requestAccountDeletion(mcFactory.forApp(ctx))
}
