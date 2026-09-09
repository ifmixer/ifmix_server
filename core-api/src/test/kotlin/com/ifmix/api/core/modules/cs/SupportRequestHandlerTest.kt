package com.ifmix.core.api.modules.cs

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isNotNull
import com.ifmix.core.api.dto.cs.CreateSupportRequestReq
import com.ifmix.core.api.entity.common.MediaRef
import com.ifmix.core.api.entity.cs.SupportRequest
import com.ifmix.core.api.entity.cs.SupportRequestStatuses
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.OperationContext
import com.ifmix.core.api.modules.cs.handler.SupportRequestAggHandler
import com.ifmix.core.api.modules.cs.repo.SupportRequestRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import java.util.UUID

/**
 * SupportRequest 创建默认值 / installId & locale 透传 / owner 登录校验（不触库）。
 * repo.save 用捕获式假实现，断言落库实体字段。
 */
class SupportRequestHandlerTest {

    private val appId = UUID.randomUUID()
    private val customerId = UUID.randomUUID()

    /** 捕获 save 的实体；查询方法此测试不覆盖。 */
    private class CapturingRepo : SupportRequestRepository() {
        var saved: SupportRequest? = null
        override fun save(mc: ModuleCtx, entity: SupportRequest): Boolean { saved = entity; return true }
    }

    private fun ctx(actorId: UUID?, installId: String?) = ModuleCtx(
        op = OperationContext(
            appId = appId,
            actorId = actorId,
            installId = installId,
            locale = "zh-CN",
            country = "CN",
            currency = "CNY",
        ),
        sql = mock<KSqlClient>(),
    )

    @Test
    fun `create sets OPEN status, null timestamps, installId and locale snapshot`() {
        val repo = CapturingRepo()
        val handler = SupportRequestAggHandler(repo)
        val req = CreateSupportRequestReq(
            title = "无法登录",
            message = "点登录没反应",
            category = 30,
            email = "a@b.com",
            phone = null,
            attachments = listOf(MediaRef(key = "ugc/x.jpg", type = 10, category = 0)),
        )

        handler.create(ctx(customerId, "install-123"), req)

        val e = repo.saved!!
        assertThat(e.status).isEqualTo(SupportRequestStatuses.OPEN)
        assertThat(e.category).isEqualTo(30)
        assertThat(e.title).isEqualTo("无法登录")
        assertThat(e.message).isEqualTo("点登录没反应")
        assertThat(e.email).isEqualTo("a@b.com")
        assertThat(e.installId).isEqualTo("install-123")
        assertThat(e.customerId).isEqualTo(customerId)
        assertThat(e.locale).isEqualTo("zh-CN")
        assertThat(e.country).isEqualTo("CN")
        assertThat(e.currency).isEqualTo("CNY")
        assertThat(e.attachments).isNotNull()
        assertThat(e.attachments!!.first().key).isEqualTo("ugc/x.jpg")
        assertThat(e.firstRepliedAt).isNull()
        assertThat(e.resolvedAt).isNull()
        assertThat(e.closedAt).isNull()
    }

    @Test
    fun `create allows anonymous customer (null customerId) and null installId`() {
        val repo = CapturingRepo()
        val handler = SupportRequestAggHandler(repo)
        handler.create(ctx(actorId = null, installId = null), CreateSupportRequestReq(title = "t", message = "m"))
        val e = repo.saved!!
        assertThat(e.customerId).isNull()
        assertThat(e.installId).isNull()
        assertThat(e.category).isEqualTo(0)
    }

    @Test
    fun `findMineById requires login`() {
        val handler = SupportRequestAggHandler(CapturingRepo())
        val err = assertThrows<ApiError> { handler.findMineById(ctx(actorId = null, installId = null), UUID.randomUUID()) }
        assertThat(err.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }

    @Test
    fun `findMine requires login`() {
        val handler = SupportRequestAggHandler(CapturingRepo())
        val err = assertThrows<ApiError> { handler.findMine(ctx(actorId = null, installId = null), null) }
        assertThat(err.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }
}
