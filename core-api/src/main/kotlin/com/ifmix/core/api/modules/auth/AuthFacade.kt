package com.ifmix.core.api.modules.auth

import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.auth.handler.AuthAggHandler
import com.ifmix.core.api.modules.auth.handler.CreateAnonymousRes
import com.ifmix.core.api.modules.auth.handler.DeleteAccountRes
import com.ifmix.core.api.modules.auth.handler.LoginRes
import com.ifmix.core.api.modules.auth.handler.LogoutRes
import com.ifmix.core.api.modules.auth.handler.MeRes
import com.ifmix.core.api.modules.auth.handler.RefreshRes
import org.springframework.stereotype.Service

@Service
class AuthFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: AuthAggHandler,
) {
    fun me(ctx: ActionContext): MeRes =
        handler.me(mcFactory.forProject(ctx))

    fun login(ctx: ActionContext, req: LoginReq): LoginRes =
        handler.login(mcFactory.forProject(ctx), req)

    fun refresh(ctx: ActionContext, req: RefreshReq): RefreshRes =
        handler.refresh(mcFactory.forProject(ctx), req)

    fun logout(ctx: ActionContext, req: LogoutReq): LogoutRes =
        handler.logout(mcFactory.forProject(ctx), req)

    fun createAnonymousCustomer(ctx: ActionContext): CreateAnonymousRes =
        handler.createAnonymousCustomer(mcFactory.forProject(ctx))

    fun requestAccountDeletion(ctx: ActionContext): DeleteAccountRes =
        handler.requestAccountDeletion(mcFactory.forProject(ctx))
}
