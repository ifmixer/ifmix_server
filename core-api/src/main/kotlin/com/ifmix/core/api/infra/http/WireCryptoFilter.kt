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
import tools.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.Enumeration
import kotlin.math.abs

/**
 * `x-proto-version: 2` 请求/响应 body 加密（算法与线格式见 [WireCrypto]）。头缺省/非 2 = 明文 v1，原样放行。
 *
 * 挂在最外层（[RequestLoggingFilter] +5 之外）：内层 filter / 日志 / GraphQL 看到的都是明文，业务零改动。
 * - 解密失败 → **明文** 400 + [ErrorCode.WIRE_DECRYPT_FAILED]（统一 GraphQL 错误形状，见 [GraphQlErrorBody]；
 *   错误响应不加密，客户端才能读懂并降级重发）。
 * - 设备时钟偏差 > 5min 只 warn 不拒绝（ts 仅用于观测，不做防重放）。
 * - 成功：缓存内层响应 → seal 后写回；status 保留，`x-proto-version: 2` + `application/octet-stream`。
 *   内层抛到容器的错误页不是 octet-stream，客户端按网关错误处理（同 v1，见设计 §5 降级表）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class WireCryptoFilter(
    @Value("\${app.wire-crypto.keys:}") keysSpec: String,
    private val mapper: ObjectMapper,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(WireCryptoFilter::class.java)
    private val crypto = WireCrypto.parse(keysSpec)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.getHeader(RequestHeaders.PROTO_VERSION) != "2"

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val opened = try {
            crypto.open(request.inputStream.readAllBytes())
        } catch (e: WireCryptoException) {
            log.warn("wire.decrypt.failed path={} kid={} keysConfigured={}", request.requestURI, e.kid, crypto.isEnabled)
            response.status = 400
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write(mapper.writeValueAsString(
                GraphQlErrorBody.error(ErrorCode.WIRE_DECRYPT_FAILED.externalCode, ErrorCode.WIRE_DECRYPT_FAILED.name, "bad encrypted payload")
            ))
            return
        }
        val skewMs = System.currentTimeMillis() - opened.clientTsMs
        if (abs(skewMs) > MAX_SKEW_MS) log.warn("wire.clock.skew path={} kid={} skewMs={}", request.requestURI, opened.kid, skewMs)

        val wrapped = ContentCachingResponseWrapper(response)
        chain.doFilter(DecryptedRequest(request, opened.body), wrapped)

        val sealed = opened.seal(wrapped.contentAsByteArray)
        // 成功也带 kid：轮换时按 kid 观察旧 kid 流量，降无可接受水平后删旧 kid（设计 §6）
        log.info("wire.seal.ok path={} kid={} gzip={} plain={} wire={}", request.requestURI, opened.kid, sealed[0].toInt() == 1, wrapped.contentAsByteArray.size, sealed.size)
        response.setHeader(RequestHeaders.PROTO_VERSION, "2")
        response.contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE
        response.setContentLength(sealed.size)
        response.outputStream.write(sealed)
    }

    /** 明文 body 替换原密文；Content-Type/Length 还原为 JSON（客户端只加密 JSON body）。 */
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
