package com.ifmix.api.core.modules.install

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ifmix.api.core.common.auth.testAuthJwtKeys
import com.ifmix.core.api.entity.install.Install
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.install.handler.InstallAggHandler
import com.ifmix.core.api.modules.install.repo.InstallAttestationRepository
import com.ifmix.core.api.modules.install.repo.InstallCustomerRelationRepository
import com.ifmix.core.api.modules.install.repo.InstallRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * Install PK 收敛不变量：createInstall 写入的 core_install.id、返回的 installId、
 * 与签发的 installToken 的 iid claim 必须三者一致（同一个 UUID）。
 * 纯逻辑：捕获式 InstallRepository + 真 AuthJwtService（testAuthJwtKeys 进程内生成 Ed25519 密钥）。
 */
class CreateInstallPkTest {

    private val projectId = "test-app"

    private class CapturingInstallRepo : InstallRepository() {
        var saved: Install? = null
        override fun save(mc: ModuleCtx, entity: Install): Boolean { saved = entity; return true }
    }

    private fun ctx() = ModuleCtx(
        action = ActionContext(projectId = projectId),
        sql = mock<KSqlClient>(),
    )

    @Test
    fun `createInstall writes one UUID as PK, returns it, and signs it as JWT iid`() {
        val repo = CapturingInstallRepo()
        val jwt = AuthJwtService(testAuthJwtKeys(), issuer = "test-issuer")
        val handler = InstallAggHandler(repo, InstallCustomerRelationRepository(), mock(), jwt)

        val res = handler.createInstall(ctx(), deviceInfo = null)

        // 1) PK == returned installId
        assertThat(repo.saved!!.id).isEqualTo(res.installId)
        // 2) JWT iid == returned installId
        val iid = jwt.verify(res.installToken)!!.installId
        assertThat(iid).isEqualTo(res.installId.toString())
    }
}
