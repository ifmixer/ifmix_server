package com.ifmix.api.core.common.auth

import com.ifmix.core.api.entity.common.ActorTypes
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * 任务5：createAnonymous/login/refresh 的 token+iid 规则集中在 ActionContext 校验档位。
 * 纯数据校验（不解析 JWT），锁定允许/拒绝矩阵。
 */
class ActionContextTokenRulesTest {

    private val iid = UUID.randomUUID()
    private val actor = UUID.randomUUID()

    private fun ctx(
        actorId: UUID? = null,
        tokenType: Int? = null,
        installId: UUID? = null,
    ) = ActionContext(
        projectId = "antique",
        actorId = actorId,
        tokenType = tokenType,
        installId = installId,
    )

    // ── mustGetTokenInstallId：createAnonymous / refresh 用（只要求有效 iid，token 类型不限） ──

    @Test fun `install token with iid is accepted`() {
        assertThat(
            ctx(tokenType = AuthJwtService.TOKEN_TYPE_INSTALL, installId = iid).mustGetTokenInstallId()
        ).isEqualTo(iid)
    }

    @Test fun `customer token with iid is accepted`() {
        assertThat(
            ctx(actorId = actor, tokenType = AuthJwtService.TOKEN_TYPE_CUSTOMER, installId = iid).mustGetTokenInstallId()
        ).isEqualTo(iid)
    }

    @Test fun `no token rejected when iid required`() {
        val ex = assertThrows<ApiError> { ctx().mustGetTokenInstallId() }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }

    @Test fun `token without iid rejected`() {
        val ex = assertThrows<ApiError> {
            ctx(tokenType = AuthJwtService.TOKEN_TYPE_INSTALL, installId = null).mustGetTokenInstallId()
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }

    // ── mustGetLoginInstallId：login 两类上下文 ──

    @Test fun `login accepts customer token context (type10 actor iid)`() {
        assertThat(
            ctx(actorId = actor, tokenType = AuthJwtService.TOKEN_TYPE_CUSTOMER, installId = iid)
                .mustGetLoginInstallId()
        ).isEqualTo(iid)
    }

    @Test fun `login accepts install token context (type5 no-actor iid)`() {
        assertThat(
            ctx(tokenType = AuthJwtService.TOKEN_TYPE_INSTALL, installId = iid).mustGetLoginInstallId()
        ).isEqualTo(iid)
    }

    @Test fun `login rejects no token`() {
        val ex = assertThrows<ApiError> { ctx().mustGetLoginInstallId() }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }

    @Test fun `login rejects manager token`() {
        val ex = assertThrows<ApiError> {
            ctx(actorId = actor, tokenType = AuthJwtService.TOKEN_TYPE_MANAGER, installId = iid).mustGetLoginInstallId()
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }

    @Test fun `login rejects customer token without iid`() {
        val ex = assertThrows<ApiError> {
            ctx(actorId = actor, tokenType = AuthJwtService.TOKEN_TYPE_CUSTOMER, installId = null).mustGetLoginInstallId()
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }

    @Test fun `login rejects install token without iid`() {
        val ex = assertThrows<ApiError> {
            ctx(tokenType = AuthJwtService.TOKEN_TYPE_INSTALL, installId = null).mustGetLoginInstallId()
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }
}
