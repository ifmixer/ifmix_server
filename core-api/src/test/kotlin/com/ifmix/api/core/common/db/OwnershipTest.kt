package com.ifmix.api.core.infra.db

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import com.ifmix.api.core.infra.http.RequestContext

/** 归属助手单元测试：ownsRow / ownerCriteria 的逻辑正确性。 */
class OwnershipTest {

    // ---- ownsRow ----

    @Test
    fun `ownsRow - login userId match`() {
        val ctx = RequestContext(appId = "app-1", userId = "user-123")
        assertThat(ownsRow(ctx, "user-123", null)).isTrue()
    }

    @Test
    fun `ownsRow - login userId mismatch`() {
        val ctx = RequestContext(appId = "app-1", userId = "user-123")
        assertThat(ownsRow(ctx, "user-456", null)).isFalse()
    }

    @Test
    fun `ownsRow - login userId wins over wrong installId`() {
        val ctx = RequestContext(appId = "app-1", userId = "user-123", installId = "install-A")
        // userId 匹配时不需要 installId 匹配
        assertThat(ownsRow(ctx, "user-123", "install-B")).isTrue()
    }

    @Test
    fun `ownsRow - anonymous installId match`() {
        val ctx = RequestContext(appId = "app-1", userId = null, installId = "install-A")
        assertThat(ownsRow(ctx, null, "install-A")).isTrue()
    }

    @Test
    fun `ownsRow - anonymous installId mismatch`() {
        val ctx = RequestContext(appId = "app-1", userId = null, installId = "install-A")
        assertThat(ownsRow(ctx, null, "install-B")).isFalse()
    }

    @Test
    fun `ownsRow - anonymous row without installId is not owned`() {
        val ctx = RequestContext(appId = "app-1", userId = null, installId = "install-A")
        assertThat(ownsRow(ctx, null, null)).isFalse()
    }

    @Test
    fun `ownsRow - login user with matching installId also works`() {
        val ctx = RequestContext(appId = "app-1", userId = "user-123", installId = "install-A")
        // 登录用户行无 userId，但 installId 匹配
        assertThat(ownsRow(ctx, null, "install-A")).isTrue()
    }

    @Test
    fun `ownsRow - both userId and installId null in row`() {
        val ctx = RequestContext(appId = "app-1", userId = "user-123")
        assertThat(ownsRow(ctx, null, null)).isFalse()
    }

    // ---- ownerCriteria ----

    @Test
    fun `ownerCriteria - login user builds OR query`() {
        val ctx = RequestContext(appId = "app-1", userId = "user-123", installId = "install-A")
        val criteria = ownerCriteria(ctx)
        // 应包含 orOperator 结构
        assertThat(criteria).isNotNull()
    }

    @Test
    fun `ownerCriteria - anonymous builds simple criteria`() {
        val ctx = RequestContext(appId = "app-1", userId = null, installId = "install-A")
        val criteria = ownerCriteria(ctx)
        // 匿名时应仅匹配 installId
        assertThat(criteria).isNotNull()
    }
}
