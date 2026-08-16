package com.ifmix.api.core.graphql.trusted

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.ifmix.api.core.graphql.common.trusted.ClasspathPersistedQueryStore
import com.ifmix.api.core.graphql.common.trusted.TrustedDocumentFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.lang.reflect.Method

class TrustedDocumentFilterTest {

    private lateinit var store: ClasspathPersistedQueryStore
    private lateinit var filter: TrustedDocumentFilter

    @BeforeEach
    fun setup() {
        store = ClasspathPersistedQueryStore("classpath:graphql/persisted-queries/")
        store.init()
        filter = TrustedDocumentFilter(store, enabled = false)
    }

    /** 通过反射调用 protected doFilterInternal，避免包访问限制。 */
    private fun doFilter(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val method: Method = TrustedDocumentFilter::class.java
            .getDeclaredMethod("doFilterInternal", HttpServletRequest::class.java, HttpServletResponse::class.java, FilterChain::class.java)
        method.isAccessible = true
        method.invoke(filter, request, response, chain)
    }

    @Test
    fun `x-op-id header finds entry and injects query`() {
        val request = mockHttpServletRequest("/customer/graphql", headers = mapOf("x-op-id" to "GetTodos"))
        val response = mockHttpServletResponse()
        val chain = RecordingFilterChain()

        doFilter(request, response, chain)

        assertThat(chain.didCall).isEqualTo(true)
        val wrappedRequest = chain.requestOrNull
        assertThat(wrappedRequest).isNotNull()
        val body = wrappedRequest!!.inputStream.readBytes().toString(Charsets.UTF_8)
        assertThat(body).contains("query GetTodos")
        assertThat(body).contains("GetTodos")
    }

    @Test
    fun `x-op-id with variables extracts them`() {
        val body = """{"variables":{"cursor":"abc","limit":10}}"""
        val request = mockHttpServletRequest(
            "/customer/graphql",
            body = body,
            headers = mapOf("x-op-id" to "GetTodos"),
        )
        val response = mockHttpServletResponse()
        val chain = RecordingFilterChain()

        doFilter(request, response, chain)

        val wrappedRequest = chain.requestOrNull
        assertThat(wrappedRequest).isNotNull()
        val output = wrappedRequest!!.inputStream.readBytes().toString(Charsets.UTF_8)
        assertThat(output).contains("\"cursor\":\"abc\"")
        assertThat(output).contains("\"limit\":10")
    }

    @Test
    fun `x-op-id with empty body uses empty variables`() {
        val request = mockHttpServletRequest(
            "/customer/graphql",
            body = "{}",
            headers = mapOf("x-op-id" to "GetTodo"),
        )
        val response = mockHttpServletResponse()
        val chain = RecordingFilterChain()

        doFilter(request, response, chain)

        val wrappedRequest = chain.requestOrNull
        assertThat(wrappedRequest).isNotNull()
        val output = wrappedRequest!!.inputStream.readBytes().toString(Charsets.UTF_8)
        assertThat(output).contains("\"variables\":{}")
    }

    @Test
    fun `x-op-id not found returns 404`() {
        val request = mockHttpServletRequest("/customer/graphql", headers = mapOf("x-op-id" to "NoSuchOp"))
        val response = mockHttpServletResponse()
        val chain = RecordingFilterChain()

        doFilter(request, response, chain)

        assertThat(chain.didCall).isEqualTo(false)
        verify(response).sendError(404, "Unknown operation: NoSuchOp")
    }

    @Test
    fun `no x-op-id falls through to hash-based logic`() {
        val body = """{"extensions":{"persistedQuery":{"sha256Hash":"a1b2c3d4e5f6"}},"variables":{"id":"123"}}"""
        val request = mockHttpServletRequest("/customer/graphql", body = body)
        val response = mockHttpServletResponse()
        val chain = RecordingFilterChain()

        doFilter(request, response, chain)

        assertThat(chain.didCall).isEqualTo(true)
        val wrappedRequest = chain.requestOrNull
        assertThat(wrappedRequest).isNotNull()
        val output = wrappedRequest!!.inputStream.readBytes().toString(Charsets.UTF_8)
        assertThat(output).contains("query GetTodo")
    }

    @Test
    fun `non-graphql path passes through unchanged`() {
        val request = mockHttpServletRequest("/other/path")
        val response = mockHttpServletResponse()
        val chain = RecordingFilterChain()

        doFilter(request, response, chain)

        // Should pass through original request
        assertThat(chain.requestOrNull).isEqualTo(request)
    }

    @Test
    fun `x-op-id works for admin path`() {
        val request = mockHttpServletRequest("/admin/graphql", headers = mapOf("x-op-id" to "AdminGetTodos"))
        val response = mockHttpServletResponse()
        val chain = RecordingFilterChain()

        doFilter(request, response, chain)

        val wrappedRequest = chain.requestOrNull
        assertThat(wrappedRequest).isNotNull()
        val body = wrappedRequest!!.inputStream.readBytes().toString(Charsets.UTF_8)
        assertThat(body).contains("query AdminGetTodos")
        assertThat(body).contains("AdminGetTodos")
    }

    private fun mockHttpServletRequest(
        uri: String,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
    ): HttpServletRequest {
        val req = mock<HttpServletRequest>()
        whenever(req.requestURI).thenReturn(uri)
        whenever(req.getHeader("x-op-id")).thenReturn(headers["x-op-id"])
        headers.forEach { (name, value) ->
            if (name != "x-op-id") whenever(req.getHeader(name)).thenReturn(value)
        }
        val stream = java.io.ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        whenever(req.getInputStream()).thenReturn(object : jakarta.servlet.ServletInputStream() {
            override fun read(): Int = stream.read()
            override fun isFinished() = stream.available() == 0
            override fun isReady() = true
            override fun setReadListener(listener: jakarta.servlet.ReadListener?) {}
        })
        return req
    }

    private fun mockHttpServletResponse(): HttpServletResponse = mock()

    private class RecordingFilterChain : FilterChain {
        var didCall = false
        var requestOrNull: HttpServletRequest? = null

        override fun doFilter(request: jakarta.servlet.ServletRequest, response: jakarta.servlet.ServletResponse) {
            didCall = true
            requestOrNull = request as? HttpServletRequest
        }
    }
}
