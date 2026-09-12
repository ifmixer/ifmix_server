package com.ifmix.core.api.modules.auth

import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.OperationContext
import com.ifmix.core.api.modules.auth.handler.AuthAggHandler
import com.ifmix.core.api.modules.auth.handler.CreateAnonymousRes
import com.ifmix.core.api.modules.auth.handler.DeleteAccountRes
import com.ifmix.core.api.modules.auth.handler.LoginReq
import com.ifmix.core.api.modules.auth.handler.LoginRes
import com.ifmix.core.api.modules.auth.handler.LogoutReq
import com.ifmix.core.api.modules.auth.handler.LogoutRes
import com.ifmix.core.api.modules.auth.handler.MeRes
import com.ifmix.core.api.modules.auth.handler.RefreshReq
import com.ifmix.core.api.modules.auth.handler.RefreshRes
import org.springframework.stereotype.Service

@Service
class AuthFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: AuthAggHandler,
) {
    fun me(ctx: OperationContext): MeRes =
        handler.me(mcFactory.forProject(ctx))

    fun login(ctx: OperationContext, req: LoginReq): LoginRes =
        handler.login(mcFactory.forProject(ctx), req)

    fun refresh(ctx: OperationContext, req: RefreshReq): RefreshRes =
        handler.refresh(mcFactory.forProject(ctx), req)

    fun logout(ctx: OperationContext, req: LogoutReq): LogoutRes =
        handler.logout(mcFactory.forProject(ctx), req)

    fun createAnonymousCustomer(ctx: OperationContext): CreateAnonymousRes =
        handler.createAnonymousCustomer(mcFactory.forProject(ctx))

    fun requestAccountDeletion(ctx: OperationContext): DeleteAccountRes =
        handler.requestAccountDeletion(mcFactory.forProject(ctx))
}
