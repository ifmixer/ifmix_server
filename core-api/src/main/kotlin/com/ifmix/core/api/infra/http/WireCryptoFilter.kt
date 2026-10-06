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
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.Enumeration
import kotlin.math.abs

/**
 * `x-wirep-version: 2` 请求/响应 body 加密（RFC 9180 HPKE，线格式见 [WireCrypto]）。
 *
 * **无版本协商**（app 未上线，无兼容负担；历史上的 2/3 草案版本已合并为 2）：
 * 头缺省视为当前版本；头存在则必须等于 [WIRE_VERSION_VALUE]，否则 400004。
 *
 * 强制策略 [mode]（`app.wire-crypto.mode`）：
 * - `required`（默认，线上）：`/customer/core/…（GraphQL 端点）` 请求必须加密——明文/版本不符 → 明文 400 + [ErrorCode.WIRE_REQUIRED]；
 *   密文解密失败 → 明文 400 + [ErrorCode.WIRE_DECRYPT_FAILED]。`/customer/core/…（GraphQL 端点）` 之外的路径
 *   （webhook / wellknown / actuator / docs）不加密，原样放行。
 * - `optional`（仅 local/dev 调试）：明文请求原样放行（带 [RequestHeaders.REQ_META] 时合并为伪 header，见下），加密请求照常处理。
 *
 * **header 进 body（wire 设计 §9 v2.1）**：加密 payload 的 JSON 为 `{meta, authorization, query, variables}`；
 * 解密后本 filter 把 `meta`（header 名为 key，仅白名单键）与顶层 `authorization`（完整 header 值，如
 * `Bearer xxx`）注入为伪 header（body 值优先于真实 header），AuthInterceptor / RequestParser 无感知。
 * 白名单外的 meta 键一律忽略——CF 注入头（cf-bot-score、真实 IP）与 `x-req-id` 永远只能来自真实 header，
 * 不会被 body 伪造。payload 违反结构（meta 非对象 / 值非字符串 / 超长）按 400003 拒绝。
 *
 * 挂在最外层（[RequestLoggingFilter] +5 之外）：内层 filter / 日志 / 业务看到的都是明文，业务零改动。
 * - 设备时钟偏差 > 5min 只 warn 不拒绝（ts 仅用于观测，不做防重放）。
 * - 成功：缓存内层响应 → seal 后写回；status 保留，`x-wirep-version: 2` + `application/octet-stream`。
 *   内层抛到容器的错误页不是 octet-stream，客户端按网关错误处理（见 wire 设计 §5）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class WireCryptoFilter(
    @Value("\${app.wire-crypto.keys:}") keysSpec: String,
    @Value("\${app.wire-crypto.max-decompressed-bytes:1048576}") maxDecompressedBytes: Int,
    @Value("\${app.wire-crypto.mode:required}") mode: String,
    private val mapper: ObjectMapper,
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
        if (encrypted || required) {
            if (headerVersion != null && headerVersion != WIRE_VERSION_VALUE) {
                reject(response, ErrorCode.WIRE_REQUIRED, "unsupported ${RequestHeaders.WIREP_VERSION}: $headerVersion")
                return
            }
            if (!encrypted) {
                // required 模式下的明文 /customer/core/…（GraphQL 端点） 请求：一律拒绝（线上无明文降级）。
                log.warn("wire.required.plain path={}", request.requestURI)
                reject(response, ErrorCode.WIRE_REQUIRED, "wire encryption required")
                return
            }
        } else {
            // optional 模式 + 明文：原样放行（本地 curl 调试）。带 x-req-meta 时解析并合并为伪 header
            //（加密请求不需要它——meta 在加密 body 里，见 extractMeta）。
            val rawMeta = request.getHeader(RequestHeaders.REQ_META)
            if (rawMeta.isNullOrBlank()) {
                chain.doFilter(request, response)
            } else {
                val meta = try {
                    parseMetaObject(rawMeta)
                } catch (e: BadMetaException) {
                    reject(response, ErrorCode.INVALID_REQUEST, "invalid ${RequestHeaders.REQ_META}: ${e.message}")
                    return
                }
                chain.doFilter(MetaHeaderRequest(request, meta), response)
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
        if (abs(skewMs) > MAX_SKEW_MS) log.warn("wire.clock.skew path={} kid={} skewMs={}", request.requestURI, opened.kid, skewMs)

        val (meta, innerBody) = try {
            splitMeta(opened.body)
        } catch (e: BadMetaException) {
            log.warn("wire.payload.bad path={} kid={} reason={}", request.requestURI, opened.kid, e.message)
            reject(response, ErrorCode.WIRE_DECRYPT_FAILED, "bad encrypted payload")
            return
        }
        val wrapped = ContentCachingResponseWrapper(response)
        chain.doFilter(DecryptedRequest(request, innerBody, meta), wrapped)

        val sealed = opened.seal(wrapped.contentAsByteArray)
        // 成功也带 kid：轮换时按 kid 观察旧 kid 流量，降无可接受水平后删旧 kid（设计 §6）
        log.info("wire.seal.ok path={} kid={} gzip={} plain={} wire={}", request.requestURI, opened.kid, sealed[0].toInt() == 1, wrapped.contentAsByteArray.size, sealed.size)
        response.setHeader(RequestHeaders.WIREP_VERSION, WIRE_VERSION_VALUE)
        response.contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE
        response.setContentLength(sealed.size)
        response.outputStream.write(sealed)
    }

    private fun reject(response: HttpServletResponse, code: ErrorCode, msg: String) {
        response.status = 400
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write("""{"code":"${code.externalCode}","msg":"$msg","data":null}""")
    }

    /**
     * 解密后 payload（`{meta, authorization, query, variables}`）拆分：取白名单内 meta + 顶层
     * authorization 为伪 header 映射，剩余键（query/variables/operationName/…）原样重序列化为内层 body。
     * 结构违规（非 JSON 对象 / meta 非对象 / 值非字符串 / 超长）抛 [BadMetaException] → 400003。
     */
    @Suppress("UNCHECKED_CAST")
    private fun splitMeta(body: ByteArray): Pair<Map<String, String>, ByteArray> {
        val root: MutableMap<String, Any> = try {
            mapper.readValue(body, MutableMap::class.java) as MutableMap<String, Any>
        } catch (_: JacksonException) {
            throw BadMetaException("payload is not a JSON object")
        }
        val meta = LinkedHashMap<String, String>()
        when (val rawMeta = root.remove("meta")) {
            null -> {}
            is Map<*, *> -> collectMeta(meta, rawMeta)
            else -> throw BadMetaException("meta must be an object")
        }
        when (val auth = root.remove("authorization")) {
            null -> {}
            is String -> if (auth.isNotEmpty()) meta["authorization"] = auth
            else -> throw BadMetaException("authorization must be a string")
        }
        return meta to mapper.writeValueAsBytes(root)
    }

    /** `x-req-meta` header 值（明文 dev 通道）解析为伪 header 映射；结构与超长约束同 [splitMeta]。 */
    @Suppress("UNCHECKED_CAST")
    private fun parseMetaObject(raw: String): Map<String, String> {
        if (raw.length > MAX_META_CHARS) throw BadMetaException("meta too large")
        val root: MutableMap<String, Any> = try {
            mapper.readValue(raw, MutableMap::class.java) as MutableMap<String, Any>
        } catch (_: JacksonException) {
            throw BadMetaException("not a JSON object")
        }
        val meta = LinkedHashMap<String, String>()
        collectMeta(meta, root)
        return meta
    }

    /** 按 [META_ALLOWED] 白名单收集（key 不区分大小写，非白名单键忽略）；值必须全为字符串；超长拒绝。 */
    private fun collectMeta(into: MutableMap<String, String>, raw: Map<*, *>) {
        var size = 0
        for ((k, v) in raw) {
            val name = k.toString().lowercase()
            if (name !in META_ALLOWED) continue
            if (v !is String) throw BadMetaException("meta value must be a string: $name")
            size += name.length + v.length
            if (size > MAX_META_CHARS) throw BadMetaException("meta too large")
            into[name] = v
        }
    }

    /** meta/payload 结构违规。 */
    private class BadMetaException(message: String) : RuntimeException(message)

    /** 明文 body 替换原密文；Content-Type/Length 还原为 JSON（客户端只加密 JSON body）；meta 注入为伪 header（body 值优先）。 */
    private class DecryptedRequest(
        req: HttpServletRequest,
        private val body: ByteArray,
        private val meta: Map<String, String>,
    ) : HttpServletRequestWrapper(req) {
        private fun metaValue(name: String): String? = meta[name.lowercase()]
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
            else -> metaValue(name) ?: super.getHeader(name)
        }
        override fun getHeaders(name: String): Enumeration<String> = when {
            name.equals(HttpHeaders.CONTENT_TYPE, true) || name.equals(HttpHeaders.CONTENT_LENGTH, true) ->
                Collections.enumeration(listOf(getHeader(name)))
            else -> metaValue(name)?.let { Collections.enumeration(listOf(it)) } ?: super.getHeaders(name)
        }
        override fun getHeaderNames(): Enumeration<String> =
            Collections.enumeration(super.getHeaderNames().toList() + meta.keys)
    }

    /** 明文请求的 `x-req-meta` 合并包装：meta 值优先于同名真实 header（仅白名单键）。 */
    private class MetaHeaderRequest(req: HttpServletRequest, private val meta: Map<String, String>) :
        HttpServletRequestWrapper(req) {
        private fun metaValue(name: String): String? = meta[name.lowercase()]
        override fun getHeader(name: String): String? = metaValue(name) ?: super.getHeader(name)
        override fun getHeaders(name: String): Enumeration<String> =
            metaValue(name)?.let { Collections.enumeration(listOf(it)) } ?: super.getHeaders(name)
        override fun getHeaderNames(): Enumeration<String> =
            Collections.enumeration(super.getHeaderNames().toList() + meta.keys)
    }

    companion object {
        private const val MAX_SKEW_MS = 5 * 60 * 1000L
        /** 业务 GraphQL 面路径前缀（wire 强制范围；gql 与 greq 同挂 /customer/core/ 之下）。 */
        const val GQL_PATH_PREFIX = "/customer/core/"
        /** 当前线协议版本（与 [WireCrypto.VERSION] 一致）。 */
        const val WIRE_VERSION_VALUE = "2"

        /** 允许从加密 body.meta / x-req-meta 注入的 header 白名单（小写）。CF 注入头与 x-req-id 永远真实 header。 */
        private val META_ALLOWED = setOf(
            RequestHeaders.PROJECT_ID,
            RequestHeaders.CLIENT_PLATFORM,
            RequestHeaders.LOCALE,
            RequestHeaders.CURRENCY,
            RequestHeaders.COUNTRY,
            RequestHeaders.APP_VERSION,
            RequestHeaders.OTA_VERSION,
            "authorization",
        )

        /** meta 键+值总字符数上限（量级对齐 Tomcat 8KB 请求头区；超限大声拒绝，防把 meta 当垃圾场）。 */
        private const val MAX_META_CHARS = 8 * 1024
    }
}
