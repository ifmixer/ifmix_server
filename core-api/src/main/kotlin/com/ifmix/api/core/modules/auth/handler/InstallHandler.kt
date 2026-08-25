package com.ifmix.api.core.modules.auth.handler

import com.ifmix.api.core.generated.types.RegisterInstallInput
import com.ifmix.api.core.generated.types.RegisterInstallResult
import com.ifmix.api.core.infra.auth.AuthJwtService
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.modules.user.UserFacade
import org.springframework.stereotype.Component

@Component
class InstallHandler(
    private val userFacade: UserFacade,
    private val jwtService: AuthJwtService,
) {
    fun register(mc: ModuleCtx, input: RegisterInstallInput): RegisterInstallResult {
        val appId = mc.op.mustGetAppId()

        val installId = userFacade.createInstall(mc.op, input)

        val token = jwtService.signInstall(installId.toString(), appId.toString())
        return RegisterInstallResult(
            installId = installId,
            token = token,
            expiresIn = jwtService.installTtlSec.toInt(),
        )
    }
}
