package com.ifmix.core.api.infra.graphql

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.GENERIC_SERVER_ERROR_MESSAGE
import graphql.execution.ExecutionStepInfo
import graphql.execution.ResultPath
import graphql.language.Field
import graphql.schema.DataFetchingEnvironment
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/** GraphQL 错误透出：线上 5xx / 未预期异常只给通用文案，4xx 原样；测试环境透出细节。 */
class GraphQLErrorExposureTest {

    private val stepInfo = mock<ExecutionStepInfo> { on { path } doReturn ResultPath.rootPath() }
    private val env = mock<DataFetchingEnvironment> {
        on { executionStepInfo } doReturn stepInfo
        on { field } doReturn Field("m_ai_scan_createOne")
    }

    private fun msg(exposeErrors: Boolean, ex: Throwable) =
        GraphQLExceptionHandler(exposeErrors).resolveException(ex, env).block()!!.single().message

    @Test
    fun `prod hides 5xx and unhandled, keeps 4xx`() {
        assertThat(msg(false, ApiError(ErrorCode.AI_UNAVAILABLE, "All AI models exhausted"))).isEqualTo(GENERIC_SERVER_ERROR_MESSAGE)
        assertThat(msg(false, RuntimeException("redis down at 10.0.0.1"))).isEqualTo(GENERIC_SERVER_ERROR_MESSAGE)
        assertThat(msg(false, ApiError(ErrorCode.QUOTA_EXCEEDED, "scan quota exhausted"))).isEqualTo("scan quota exhausted")
    }

    @Test
    fun `test env exposes details`() {
        assertThat(msg(true, ApiError(ErrorCode.AI_UNAVAILABLE, "All AI models exhausted"))).isEqualTo("All AI models exhausted")
        assertThat(msg(true, RuntimeException("boom"))).isEqualTo("boom")
    }
}
