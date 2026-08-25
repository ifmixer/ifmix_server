package com.ifmix.api.core.modules.user

import com.ifmix.api.core.entity.user.AppUser
import com.ifmix.api.core.entity.user.Install
import com.ifmix.api.core.generated.types.RegisterInstallInput
import com.ifmix.api.core.infra.db.ModuleCtxFactory
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.user.repo.AppUserRepository
import com.ifmix.api.core.modules.user.repo.InstallRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class UserFacade(
    private val mcFactory: ModuleCtxFactory,
    private val appUserRepo: AppUserRepository,
    private val installRepo: InstallRepository,
) {
    fun findById(ctx: OperationContext, id: UUID): AppUser? =
        appUserRepo.findById(mcFactory.forApp(ctx), ctx.mustGetAppId(), id)

    fun createAppUser(ctx: OperationContext): UUID =
        appUserRepo.createAppUser(mcFactory.forApp(ctx), ctx.mustGetAppId())

    fun exists(ctx: OperationContext, id: UUID): Boolean =
        appUserRepo.exists(mcFactory.forApp(ctx), ctx.mustGetAppId(), id)

    fun createInstall(ctx: OperationContext, input: RegisterInstallInput): UUID {
        val mc = mcFactory.forApp(ctx)
        val now = Instant.now()
        val id = UuidV7.generate()
        val entity = Install {
            this.id = id
            this.appId = ctx.mustGetAppId()
            this.platform = input.platform
            this.nativeVersion = input.nativeVersion
            this.jsVersion = input.jsVersion
            this.clientIp = ctx.clientIp
            this.info = input.info as? Map<String, Any?>
            this.createdAt = now
            this.updatedAt = now
        }
        installRepo.save(mc, entity)
        return id
    }
}
