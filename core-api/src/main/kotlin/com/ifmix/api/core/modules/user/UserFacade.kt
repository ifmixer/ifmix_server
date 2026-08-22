package com.ifmix.api.core.modules.user

import com.ifmix.api.core.entity.user.AppUser
import com.ifmix.api.core.infra.db.ModuleCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.user.repo.AppUserRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class UserFacade(
    private val mcFactory: ModuleCtxFactory,
    private val appUserRepo: AppUserRepository,
) {
    fun findById(ctx: OperationContext, id: UUID): AppUser? =
        appUserRepo.findById(mcFactory.forApp(ctx), ctx.mustGetAppId(), id)

    fun createAppUser(ctx: OperationContext): UUID =
        appUserRepo.createAppUser(mcFactory.forApp(ctx), ctx.mustGetAppId())

    fun exists(ctx: OperationContext, id: UUID): Boolean =
        appUserRepo.exists(mcFactory.forApp(ctx), ctx.mustGetAppId(), id)
}
