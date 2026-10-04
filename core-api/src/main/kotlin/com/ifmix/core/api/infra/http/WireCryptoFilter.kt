package com.ifmix.core.api.infra.http

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.Enumeration
import kotlin.math.abs

/**
 * `x-proto-version: 2` 请求/响应 body 加密（算法见 [WireCrypto]）。不带该头（或为 1）= 明文，老客户端不受影响。
 *
 * 最外层（在 [RequestLoggingFilter] 之外）：内层 filter / 日志 / GraphQL 看到的都是明文，业务零改动。
 * - 解密失败 → **明文** 400 + 400003（客户端无法解密响应时也能读懂），客户端据此降级明文重试。
 * - 客户端时间与服务端差 > 5min 只 warn 不拒绝（防设备时间不准误伤正常用户；暂不做防重放）。
 * - 成功：响应 body 加密，响应头 `x-proto-version: 2` + `application/octet-stream`；HTTP status 原样保留。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class WireCryptoFilter(
    @Value("\${app.wire-crypto.keys:}") keysSpec: String,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(WireCryptoFilter::class.java)
    private val crypto = WireCrypto.parse(keysSpec)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.getHeader(RequestHeaders.PROTO_VERSION) != "2"

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val opened = try {
            crypto.open(request.inputStream.readAllBytes())
        } catch (_: WireCryptoException) {
            log.warn("wire.decrypt.failed path={} keysConfigured={}", request.requestURI, crypto.isEnabled)
            response.status = 400
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write("""{"code":"${ErrorCode.WIRE_DECRYPT_FAILED.externalCode}","msg":"bad encrypted payload","data":null}""")
            return
        }
        val skewMs = System.currentTimeMillis() - opened.clientTsMs
        if (abs(skewMs) > MAX_SKEW_MS) log.warn("wire.clock.skew path={} skewMs={}", request.requestURI, skewMs)

        val wrapped = ContentCachingResponseWrapper(response)
        chain.doFilter(DecryptedRequest(request, opened.body), wrapped)

        val sealed = opened.seal(wrapped.contentAsByteArray)
        response.setHeader(RequestHeaders.PROTO_VERSION, "2")
        response.contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE
        response.setContentLength(sealed.size)
        response.outputStream.write(sealed)
    }

    /** 明文 body 替换原密文；content-type 还原为 JSON（客户端只加密 JSON body）。 */
    private class DecryptedRequest(req: HttpServletRequest, private val body: ByteArray) : HttpServletRequestWrapper(req) {
        override fun getInputStream(): ServletInputStream {
            val input = ByteArrayInputStream(body)
            return object : ServletInputStream() {
                override fun read() = input.read()
                override fun read(b: ByteArray, off: Int, len: Int) = input.read(b, off, len)
                override fun isFinished() = input.available() == 0
                override fun isReady() = true
                override fun setReadListener(l: ReadListener) = throw UnsupportedOperationException()
            }
        }
        override fun getReader() = BufferedReader(inputStream.reader(Charsets.UTF_8))
        override fun getContentType() = MediaType.APPLICATION_JSON_VALUE
        override fun getContentLength() = body.size
        override fun getContentLengthLong() = body.size.toLong()
        override fun getCharacterEncoding() = "UTF-8"
        override fun getHeader(name: String): String? = when {
            name.equals(HttpHeaders.CONTENT_TYPE, true) -> contentType
            name.equals(HttpHeaders.CONTENT_LENGTH, true) -> body.size.toString()
            else -> super.getHeader(name)
        }
        override fun getHeaders(name: String): Enumeration<String> =
            if (name.equals(HttpHeaders.CONTENT_TYPE, true) || name.equals(HttpHeaders.CONTENT_LENGTH, true)) {
                Collections.enumeration(listOf(getHeader(name)))
            } else {
                super.getHeaders(name)
            }
    }

    companion object {
        private const val MAX_SKEW_MS = 5 * 60 * 1000L
    }
}
