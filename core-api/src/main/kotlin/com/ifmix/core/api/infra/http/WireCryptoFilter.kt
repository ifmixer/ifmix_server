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
 * - `optional`（仅 local/dev 调试）：明文请求原样放行（带 [RequestHeaders.REQ_META] 时解析为 [RequestMeta]
 *   挂到 request attribute 上，见下），加密请求照常处理。
 *
 * **meta 进 body（wire 设计 §9）**：加密 payload 的 JSON 为 `{meta, authorization, query, variables}`；
 * meta 段是 [RequestMeta] 结构（key 为普通字段名：`projectId` / `locale` / `clientPlatform` …，**不是**
 * header 名，全部字符串值，未知字段忽略）。解密后本 filter 把顶层 `authorization` 剥掉 `Bearer ` 前缀
 * 存入 `RequestMeta.accessToken`，连同 meta 段一起合并为一个 [RequestMeta] 实例挂到 request attribute
 *（[RequestParser.ATTR_META]），RequestParser 直接取对象——**不再回写请求头、不做伪 header**；
 * 解密后的内层 body 只剩 `query`/`variables` 等剩余键。meta 段整体大小超限（解压后 > [MAX_META_BYTES]）
 * 按 400003 拒绝；authorization 必须是字符串。
 *
 * 明文 dev 通道（optional 模式）：`x-req-meta` header 值为同一个 [RequestMeta] JSON（key 同上，不含
 * 凭证——token 走标准 `Authorization` header，RequestParser 解析时 meta.accessToken 优先、缺省回落
 * Authorization）。超长同样拒绝。`x-req-id` 永远只能来自真实 header。
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
            // optional 模式 + 明文：原样放行。带 x-req-meta 时解析为 RequestMeta 挂到 request attribute
            //（加密请求不需要它——meta 在加密 body 里，见 parsePayloadMeta）。
            val rawMeta = request.getHeader(RequestHeaders.REQ_META)
            if (rawMeta.isNullOrBlank()) {
                chain.doFilter(request, response)
            } else {
                val meta = try {
                    mapper.readValue(rawMeta, RequestMeta::class.java)
                } catch (e: JacksonException) {
                    reject(response, ErrorCode.INVALID_REQUEST, "invalid ${RequestHeaders.REQ_META}: ${e.message}")
                    return
                }
                chain.doFilter(WithMeta(request, meta), response)
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

        val (meta, innerBody) = try {
            parsePayloadMeta(opened.body)
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
     * 解密后 payload（`{meta, authorization, query, variables}`）拆分：meta 段绑定为 [RequestMeta]
     *（Jackson 原生映射，未知字段忽略），顶层 `authorization` 剥 `Bearer ` 前缀存入 [RequestMeta.accessToken]；
     * 剩余键（query/variables/operationName/…）原样重序列化为内层 body。
     * 结构违规（payload 非对象 / meta 非对象 / 授权非字符串 / meta 段超长）抛 [BadMetaException] → 400003。
     */
    private fun parsePayloadMeta(body: ByteArray): Pair<RequestMeta, ByteArray> {
        val root: MutableMap<String, Any> = try {
            mapper.readValue(body, MutableMap::class.java) as MutableMap<String, Any>
        } catch (_: JacksonException) {
            throw BadMetaException("payload is not a JSON object")
        }
        val meta = when (val rawMeta = root.remove("meta")) {
            null -> RequestMeta()
            is Map<*, *> -> {
                // meta 段（key+值）总大小上限，防把 meta 当垃圾场（量级对齐 Tomcat 8KB 请求头区）
                val size = rawMeta.entries.sumOf { it.key.toString().length + it.value.toString().length }
                if (size > MAX_META_BYTES) throw BadMetaException("meta too large")
                try {
                    mapper.convertValue(rawMeta, RequestMeta::class.java)
                } catch (e: JacksonException) {
                    // 值非字符串（如嵌套对象/数字）→ 结构违规，与 x-req-meta 通道一致按 400003 拒绝
                    throw BadMetaException("meta value must be a string")
                }
            }
            else -> throw BadMetaException("meta must be an object")
        }
        val auth = root.remove("authorization")
        val accessToken = when (auth) {
            null -> meta.accessToken
            is String -> auth.trim()
                .takeIf { it.isNotEmpty() }
                ?.takeUnless { it.equals("Bearer", ignoreCase = true) }
                ?.let { if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim().takeIf { s -> s.isNotEmpty() } else it }
                ?: meta.accessToken
            else -> throw BadMetaException("authorization must be a string")
        }
        val merged = if (accessToken == null) meta else meta.copy(accessToken = accessToken)
        return merged to mapper.writeValueAsBytes(root)
    }

    /** meta/payload 结构违规。 */
    private class BadMetaException(message: String) : RuntimeException(message)

    /** 解密后 body 替换原密文；Content-Type/Length 还原为 JSON（客户端只加密 JSON body）。 */
    private class DecryptedRequest(
        req: HttpServletRequest,
        private val body: ByteArray,
        private val meta: RequestMeta,
    ) : HttpServletRequestWrapper(req) {
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
        override fun getAttribute(name: String): Any? =
            if (name == RequestMeta.ATTR_META) meta else super.getAttribute(name)
    }

    /** 明文 dev 通道的 `x-req-meta`：解析出的 [RequestMeta] 挂到 request attribute（后续 RequestParser 直接取）。 */
    private class WithMeta(req: HttpServletRequest, private val meta: RequestMeta) : HttpServletRequestWrapper(req) {
        override fun getAttribute(name: String): Any? =
            if (name == RequestMeta.ATTR_META) meta else super.getAttribute(name)
    }

    companion object {
        private const val MAX_SKEW_MS = 5 * 60 * 1000L
        /** 业务 GraphQL 面路径前缀（wire 强制范围；gql 与 greq 同挂 /customer/core/ 之下）。 */
        const val GQL_PATH_PREFIX = "/customer/core/"
        /** 当前线协议版本（与 [WireCrypto.VERSION] 一致）。 */
        const val WIRE_VERSION_VALUE = "2"
        /** meta 段（key+值）总字符数上限；超限 400003 / 400000 大声拒绝。 */
        private const val MAX_META_BYTES = 8 * 1024
    }
}
