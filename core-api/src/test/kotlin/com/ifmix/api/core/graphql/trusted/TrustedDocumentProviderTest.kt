package com.ifmix.core.api.infra.graphql.trusted

import graphql.ExecutionInput
import graphql.execution.preparsed.PreparsedDocumentEntry
import graphql.parser.Parser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.function.Function

/**
 * TrustedDocumentProvider 核心逻辑单测（不依赖 Spring/DGS）。
 * 覆盖：命中缓存 doc / 未知 apiName 拒 / 未传 apiName 在 allowRawQuery 两态的行为。
 */
class TrustedDocumentProviderTest {

    private val parser = Parser()

    private fun storeWith(vararg apis: String): PersistedQueryStore {
        val map = apis.associateWith { name ->
            val q = "query $name { __typename }"
            PersistedQueryEntry(name, q, parser.parseDocument(q))
        }
        return object : PersistedQueryStore {
            override fun getByApiName(apiName: String, bff: String) = map[apiName]
        }
    }

    /** 构造带 apiName 的 ExecutionInput；apiName=null 表示未传。 */
    private fun input(apiName: String?, rawQuery: String = "{__typename}"): ExecutionInput {
        val ctxMap: Map<Any, Any> =
            if (apiName != null) mapOf(ApiNameHeaderInterceptor.CTX_API_NAME to apiName) else emptyMap()
        return ExecutionInput.newExecutionInput().query(rawQuery).graphQLContext(ctxMap).build()
    }

    /** fallback：模拟 graphql-java 默认 parseAndValidate，返回一个可辨识的 sentinel doc。 */
    private val fallback = Function<ExecutionInput, PreparsedDocumentEntry> {
        PreparsedDocumentEntry(parser.parseDocument(it.query))
    }

    @Test
    fun `hit returns cached document`() {
        val provider = TrustedDocumentProvider(storeWith("q_auth_me"), allowRawQuery = false)
        val entry = provider.getDocumentAsync(input("q_auth_me"), fallback).get()
        assertThat(entry.hasErrors()).isFalse()
        assertThat(entry.document).isNotNull()
    }

    @Test
    fun `unknown apiName is rejected regardless of allowRawQuery`() {
        for (allowRaw in listOf(true, false)) {
            val provider = TrustedDocumentProvider(storeWith("q_auth_me"), allowRawQuery = allowRaw)
            val entry = provider.getDocumentAsync(input("q_bogus"), fallback).get()
            assertThat(entry.hasErrors()).`as`("allowRawQuery=$allowRaw").isTrue()
            assertThat(entry.errors[0].message).contains("unknown api-name")
        }
    }

    @Test
    fun `no apiName rejected when raw query not allowed`() {
        val provider = TrustedDocumentProvider(storeWith("q_auth_me"), allowRawQuery = false)
        val entry = provider.getDocumentAsync(input(null), fallback).get()
        assertThat(entry.hasErrors()).isTrue()
        assertThat(entry.errors[0].message).contains("x-api-name header is required")
    }

    @Test
    fun `no apiName falls back to raw query when allowed`() {
        val provider = TrustedDocumentProvider(storeWith("q_auth_me"), allowRawQuery = true)
        val entry = provider.getDocumentAsync(input(null, rawQuery = "{__typename}"), fallback).get()
        assertThat(entry.hasErrors()).isFalse()
        assertThat(entry.document).isNotNull()
    }
}
