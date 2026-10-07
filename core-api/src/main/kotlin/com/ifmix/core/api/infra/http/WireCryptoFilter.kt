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

/**
 * 请求/响应 body 加密（RFC 9180 HPKE，线格式见 [WireCrypto]）。
 *
 * **加密是默认，头只做明文标记**（app 未上线，无兼容负担；历史上的 2/3 草案版本已合并为 2）：
 * 加密请求（`Content-Type: application/octet-stream`）**不传** `x-wirep-version`；
 * 明文请求传 `x-wirep-version: 1`（[PLAIN_VERSION_VALUE]）。头存在且非 1 → 400004。
 * 加密响应同样不回传该头，由 `Content-Type: application/octet-stream` 标识。
 *
 * 强制策略 [mode]（`app.wire-crypto.mode`）：
 * - `required`（默认，线上）：`/customer/core/…（GraphQL 端点）` 请求必须加密——明文/版本不符 → 明文 400 + [ErrorCode.WIRE_REQUIRED]；
 *   密文解密失败 → 明文 400 + [ErrorCode.WIRE_DECRYPT_FAILED]。`/customer/core/…（GraphQL 端点）` 之外的路径
 *   （webhook / wellknown / actuator / docs）不加密，原样放行。
 * - `optional`（仅 local/dev 调试）：明文请求原样放行，加密请求照常处理。
 *
 * **职责边界：本 filter 只做加解密，不解析、不涉及业务。** 解密后的 body 原样（
 * `{authorization, meta, query, variables}` 四键全保留）替换密文向下游传递，并缓存到
 * request attribute（[ATTR_DECRYPTED_BODY]）；headers 一律原样不动。meta/authorization 的
 * 解析、校验全部在 [com.ifmix.core.api.infra.auth.RequestParser]（parseMeta / parseAuthorization）。
 *
 * 明文 dev 通道（optional 模式）：无加密 body，RequestParser 按 env 回落读 `x-req-meta` header
 * （meta）与标准 `Authorization` header（凭证）。
 *
 * 挂在最外层（[RequestLoggingFilter] +5 之外）：内层 filter / 日志 / 业务看到的都是明文，业务零改动。
 * - 设备时钟偏差 > 5min 只 warn 不拒绝（ts 仅用于观测，不做防重放）。
 * - 成功：缓存内层响应 → seal 后写回；status 保留，`application/octet-stream`（不回传版本头）。
 *   内层抛到容器的错误页不是 octet-stream，客户端按网关错误处理（见 wire 设计 §5）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class WireCryptoFilter(
    @Value("\${app.wire-crypto.keys:}") keysSpec: String,
    @Value("\${app.wire-crypto.max-decompressed-bytes:1048576}") maxDecompressedBytes: Int,
    @Value("\${app.wire-crypto.mode:required}") mode: String,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(WireCryptoFilter::class.java)
    private val crypto = WireCrypto.parse(keysSpec, maxDecompressedBytes)
    private val required = mode != "optional"

    /** 只接管业务 GraphQL 面（gql / greq）；webhook/wellknown/actuator/docs 等信封外流量不加密。 */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith(GQL_PATH_PREFIX)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val headerVersion = request.getHeader(RequestHeaders.WIREP_VERSION)
        val encrypted = request.contentType?.startsWith(MediaType.APPLICATION_OCTET_STREAM_VALUE) == true
        // 头只允许出现明文标记 "1"（加密请求不传）；其余值一律 400004
        if (headerVersion != null && headerVersion != PLAIN_VERSION_VALUE) {
            reject(response, ErrorCode.WIRE_REQUIRED, "unsupported ${RequestHeaders.WIREP_VERSION}: $headerVersion")
            return
        }
        if (!encrypted) {
            if (required) {
                // required 模式下的明文 /customer/core/…（GraphQL 端点） 请求：一律拒绝（线上无明文降级）。
                log.warn("wire.required.plain path={}", request.requestURI)
                reject(response, ErrorCode.WIRE_REQUIRED, "wire encryption required")
            } else {
                // optional 模式 + 明文：原样放行（meta/凭证由 RequestParser 按 env 回落读 headers）
                chain.doFilter(request, response)
            }
            return
        }

        val opened = try {
            crypto.open(request.inputStream.readAllBytes())
        } catch (e: WireCryptoException) {
            log.warn("wire.decrypt.failed path={} kid={} keysConfigured={}", request.requestURI, e.kid, crypto.isEnabled)
            reject(response, ErrorCode.WIRE_DECRYPT_FAILED, "bad encrypted payload")
            return
        }
        val skewMs = System.currentTimeMillis() - opened.clientTsMs
        if (kotlin.math.abs(skewMs) > MAX_SKEW_MS) log.warn("wire.clock.skew path={} kid={} skewMs={}", request.requestURI, opened.kid, skewMs)

        // 解密后的 body 原样透传（含 meta/authorization 键，由 RequestParser 解析）；headers 一律不动
        val wrapped = ContentCachingResponseWrapper(response)
        chain.doFilter(DecryptedRequest(request, opened.body), wrapped)

        val sealed = opened.seal(wrapped.contentAsByteArray)
        // 成功也带 kid：轮换时按 kid 观察旧 kid 流量，降无可接受水平后删旧 kid（设计 §6）
        log.info("wire.seal.ok path={} kid={} gzip={} plain={} wire={}", request.requestURI, opened.kid, sealed[0].toInt() == 1, wrapped.contentAsByteArray.size, sealed.size)
        response.contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE
        response.setContentLength(sealed.size)
        response.outputStream.write(sealed)
    }

    private fun reject(response: HttpServletResponse, code: ErrorCode, msg: String) {
        response.status = 400
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write("""{"code":"${code.externalCode}","msg":"$msg","data":null}""")
    }

    /** 解密后 body 替换原密文并缓存到 attribute（RequestParser 从这里取 meta/authorization）；Content-Type/Length 还原为 JSON。 */
    private class DecryptedRequest(
        req: HttpServletRequest,
        private val body: ByteArray,
    ) : HttpServletRequestWrapper(req) {
        override fun getAttribute(name: String): Any? =
            if (name == ATTR_DECRYPTED_BODY) body else super.getAttribute(name)

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
    }

    companion object {
        private const val MAX_SKEW_MS = 5 * 60 * 1000L
        /** 业务 GraphQL 面路径前缀（wire 强制范围；gql 与 greq 同挂 /customer/core/ 之下）。 */
        const val GQL_PATH_PREFIX = "/customer/core/"
        /** 明文请求的 `x-wirep-version` 标记值（加密请求不传该头）。 */
        const val PLAIN_VERSION_VALUE = "1"
        /** 解密后的 body（完整信封 JSON，含 meta/authorization 键）缓存 attribute key；RequestParser 消费。 */
        const val ATTR_DECRYPTED_BODY = "com.ifmix.wire.decryptedBody"
    }
}
