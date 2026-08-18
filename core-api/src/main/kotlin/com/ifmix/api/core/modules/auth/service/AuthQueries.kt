package com.ifmix.api.core.modules.auth.service

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.app.repo.AppConfigRepository

class AuthQueries(
    private val appConfigRepo: AppConfigRepository,
) {
    private fun svcCtx(opCtx: OperationContext): SvcCtx = SvcCtx(op = opCtx, dsl = SvcCtx.DEFAULT.dsl)

    fun me(ctx: OperationContext): MeRes {
        val userId = ctx.userId ?: throw com.ifmix.api.core.infra.http.ApiError(
            com.ifmix.api.core.infra.http.ErrorCode.UNAUTHORIZED
        )
        return MeRes(userId, null)
    }
}
