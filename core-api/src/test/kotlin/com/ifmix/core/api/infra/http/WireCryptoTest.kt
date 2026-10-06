package com.ifmix.core.api.infra.http

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isNull
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.hpke.HPKEContextWithEncapsulation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.HexFormat
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import tools.jackson.databind.json.JsonMapper
import kotlin.io.path.readText

/**
 * v3（RFC 9180 HPKE）向量驱动 + 加解密往返 + filter 行为。
 * 测试侧"客户端"用 BouncyCastle HPKE（与生产同库，seal 路径）+ JDK AES-GCM（响应 resKey 独立
 * 用客户端侧 HPKE-Export 推导，不复用 WireCrypto 代码）组装/拆解线格式；
 * RFC 官方向量（wire-v3-rfc-vectors.json，生成与来源见同目录 gen_vectors.py）逐字节锁定 HPKE 实现。
 */
class WireCryptoTest {

    private val crypto = WireCrypto(mapOf(1 to HEX.parseHex(SERVER_PRIV_HEX), 7 to ByteArray(32) { 3 }))

    // ---- RFC 官方向量（T1） ----

    @Test
    fun `rfc vectors - receiver open and sender seal match byte for byte`() {
        val mapper = JsonMapper.builder().build()
        val doc = mapper.readTree(javaClass.getResource("/wire-v3/wire-v3-rfc-vectors.json")!!.openStream().reader().readText())
        val base = doc.get("vectors").asArray()
            .filter { it.get("mode").asInt() == 0 }
            .let { check(it.size == 1) { "expect 1 base-mode vector, got ${it.size}" }; it }
        val v = base.get(0)
        val vhpke = HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_AES_GCM256)
        val info = HexFormat.of().parseHex(v.get("info").asString())
        val enc = HexFormat.of().parseHex(v.get("enc").asString())

        // 服务端侧：skRm（X25519 32B raw）→ 公钥推导须与官方 pkRm 逐字节一致
        val rKp = vhpke.deserializePrivateKey(HexFormat.of().parseHex(v.get("skRm").asString()), null)
        assertThat(vhpke.serializePublicKey(rKp.public).toHex()).isEqualTo(v.get("pkRm").asString())

        // 接收方 Open：官方向量 257 条 encryption（nonce = base_nonce XOR seq，HPKE 标准约定）逐条还原明文。
        // BC base-mode context 每 open 一次推进 seq，须与向量的 seq 递增对齐；新建一次 context 顺序消费。
        val rctx = vhpke.setupBaseR(enc, rKp, info)
        for (i in 0 until v.get("encryptions").size()) {
            val e = v.get("encryptions").get(i)
            val pt = rctx.open(HexFormat.of().parseHex(e.get("aad").asString()), HexFormat.of().parseHex(e.get("ct").asString()))
            assertThat(pt.toHex()).isEqualTo(e.get("pt").asString())
        }

        // 独立 AES-GCM 交叉验证（key/nonce 直接取自向量，不走 BC HPKE 路径）：同一 ct 必须解出同一 pt
        val first = v.get("encryptions").get(0)
        val gcm = Cipher.getInstance("AES/GCM/NoPadding")
        gcm.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(HexFormat.of().parseHex(v.get("key").asString()), "AES"),
            GCMParameterSpec(128, HexFormat.of().parseHex(first.get("nonce").asString())),
        )
        gcm.updateAAD(HexFormat.of().parseHex(first.get("aad").asString()))
        assertThat(gcm.doFinal(HexFormat.of().parseHex(first.get("ct").asString())).toHex()).isEqualTo(first.get("pt").asString())

        // 导出一致性：Export(exporter_context="", L=32) 与官方 exports[0] 相同（wire v3 的 resKey 即此机制）。
        // 用独立 context（上面 rctx 已消费 257 个 seq）。
        assertThat(vhpke.setupBaseR(enc, rKp, info).export(ByteArray(0), 32).toHex())
            .isEqualTo(v.get("exports").get(0).get("exported_value").asString())

        // 发送方 Seal：deriveKeyPair(ikmE) 复现官方 enc（enc=pkEm），逐条 seal 与官方 ct 逐字节一致。
        // 官方向量的 257 条 encryption 按 seq=0,1,2,… 递增（nonce = base_nonce XOR seq），
        // 单个 sender context 顺序 seal 即复现该 seq 递增，须跨条目复用同一 context。
        val eKp = vhpke.deriveKeyPair(HexFormat.of().parseHex(v.get("ikmE").asString()))
        val sctx = vhpke.setupBaseS(rKp.public, info, eKp)
        assertThat(sctx.encapsulation.toHex()).isEqualTo(enc.toHex())
        for (i in 0 until v.get("encryptions").size()) {
            val e = v.get("encryptions").get(i)
            val ct = sctx.seal(HexFormat.of().parseHex(e.get("aad").asString()), HexFormat.of().parseHex(e.get("pt").asString()))
            assertThat(ct.toHex()).isEqualTo(e.get("ct").asString())
        }
    }

    // ---- 端到端 round-trip（T3） ----

    @Test
    fun `round trips with independent client impl`() {
        val client = TestClient(crypto.publicKey(1)!!, kid = 1)
        val body = """{"query":"","variables":{"x":"中文"}}"""
        val opened = crypto.open(client.sealRequest(body.toByteArray(), ts = 42))
        assertThat(opened.body.toString(Charsets.UTF_8)).isEqualTo(body)
        assertThat(opened.clientTsMs).isEqualTo(42L)
        assertThat(opened.kid).isEqualTo(1)

        val sealed = opened.seal("resp-响应".toByteArray())
        assertThat(sealed[0]).isEqualTo(0.toByte()) // 小响应不 gzip
        assertThat(client.openResponse(sealed).toString(Charsets.UTF_8)).isEqualTo("resp-响应")
    }

    @Test
    fun `gzip threshold - 4096 plain, 4097 gzipped, both recoverable`() {
        val client = TestClient(crypto.publicKey(1)!!, kid = 1)
        val opened = crypto.open(client.sealRequest("x".toByteArray()))
        val plain = opened.seal(ByteArray(4096))
        assertThat(plain[0]).isEqualTo(0.toByte())
        assertThat(client.openResponse(plain).size).isEqualTo(4096)
        val gz = opened.seal(ByteArray(4097))
        assertThat(gz[0]).isEqualTo(1.toByte())
        assertThat(gz.size).isLessThan(4097)
        assertThat(client.openResponse(gz).size).isEqualTo(4097)
    }

    @Test
    fun `request gzip - pt above 4096 boundary is compressed and recovered`() {
        val client = TestClient(crypto.publicKey(1)!!, kid = 1)
        // 5KB 请求 body → pt = ts(8) + 5120 > 4096 → 客户端 gzip，服务端解压还原
        val body = "x".repeat(5120)
        val opened = crypto.open(client.sealRequest(body.toByteArray(), ts = 1))
        assertThat(opened.body.toString(Charsets.UTF_8)).isEqualTo(body)
        // 边界：pt 恰好 4096（body 4088 = 4096 - ts 8）不压；4097（body 4089）压
        val noCompress = "y".repeat(4096 - 8)
        val openedNo = crypto.open(client.sealRequest(noCompress.toByteArray(), ts = 1))
        assertThat(openedNo.body.size).isEqualTo(4096 - 8)
        val compress = "z".repeat(4097 - 8)
        val openedYes = crypto.open(client.sealRequest(compress.toByteArray(), ts = 1))
        assertThat(openedYes.body.size).isEqualTo(4097 - 8)
    }

    @Test
    fun `request decompress limit rejects zip bomb`() {
        val tiny = WireCrypto(mapOf(1 to HEX.parseHex(SERVER_PRIV_HEX)), maxDecompressedBytes = 64)
        val client = TestClient(tiny.publicKey(1)!!, kid = 1)
        // pt = ts(8) + body；body 4200 → pt 4208 > 4096 → 客户端 gzip → 解压后 4200 > 64 上限 → 拒（zip-bomb）
        val wire = client.sealRequest("a".repeat(4200).toByteArray())
        assertThrows<WireCryptoException> { tiny.open(wire) }
        // 非 gzip 小请求（pt ≤ 4096，天然在 1MB 类上限内）正常
        val okWire = client.sealRequest("b".repeat(32).toByteArray())
        assertThat(tiny.open(okWire).body.size).isEqualTo(32)
    }

    @Test
    fun `unknown flag bit rejected`() {
        val client = TestClient(crypto.publicKey(1)!!, kid = 1)
        val wire = client.sealRequest("x".toByteArray())
        val badFlags = wire.copyOf().also { it[HEADER_LEN - 1] = (it[HEADER_LEN - 1].toInt() or 0x02).toByte() }
        assertThrows<WireCryptoException> { crypto.open(badFlags) }
    }

    @Test
    fun `multi kid, unknown kid, bad enc, tampering`() {
        val c7 = TestClient(crypto.publicKey(7)!!, kid = 7)
        assertThat(crypto.open(c7.sealRequest("x".toByteArray())).body.toString(Charsets.UTF_8)).isEqualTo("x")

        val unknownKid = c7.sealRequest("x".toByteArray()).also { it[1] = 9 }
        assertThrows<WireCryptoException> { crypto.open(unknownKid) }

        // 坏 enc（全零 X25519 低阶点）：HPKE 库拒绝
        val zeroEncPayload = byteArrayOf(3, 7) + ByteArray(32) + byteArrayOf(0) + ByteArray(16 + 8)
        assertThrows<WireCryptoException> { crypto.open(zeroEncPayload) }
        // 篡改 ct（GCM 认证失败）
        val tampered = c7.sealRequest("x".toByteArray()).also { it[it.size - 1] = (it.last() + 1).toByte() }
        assertThrows<WireCryptoException> { crypto.open(tampered) }
        // 乱序/过短 payload
        val badVer = c7.sealRequest("x".toByteArray()).also { it[0] = 3 }
        assertThrows<WireCryptoException> { crypto.open(badVer) }
        assertThrows<WireCryptoException> { crypto.open(ByteArray(40)) }
    }

    @Test
    fun `inner without ts rejected`() {
        val client = TestClient(crypto.publicKey(1)!!, kid = 1)
        val noTs = client.sealRequest(ByteArray(0), inner = ByteArray(0))
        assertThrows<WireCryptoException> { crypto.open(noTs) }
    }

    @Test
    fun `empty config and config errors`() {
        val empty = WireCrypto.parse("")
        assertThat(empty.isEnabled).isEqualTo(false)
        assertThat(empty.publicKey(1)).isNull()
        val any = TestClient(crypto.publicKey(1)!!, kid = 1).sealRequest("x".toByteArray())
        assertThrows<WireCryptoException> { empty.open(any) }
        // 配置错误与 payload 错误区分：前者启动期 fail-fast
        assertThrows<IllegalArgumentException> { WireCrypto.parse("bad") }
        assertThrows<IllegalArgumentException> {
            WireCrypto.parse("0:${Base64.getEncoder().encodeToString(ByteArray(32))}")
        }
        assertThrows<IllegalArgumentException> {
            WireCrypto.parse("1:${Base64.getEncoder().encodeToString(ByteArray(31))}")
        }
    }

    @Test
    fun `response flag tampering fails on client side`() {
        val client = TestClient(crypto.publicKey(1)!!, kid = 1)
        val opened = crypto.open(client.sealRequest("x".toByteArray()))
        val sealed = opened.seal("resp".toByteArray()) // flags=0

        val unknownFlag = sealed.copyOf().also { it[0] = 2 }
        assertThrows<Exception> { client.openResponse(unknownFlag) }
        val claimedGzip = sealed.copyOf().also { it[0] = 1 }
        assertThrows<Exception> { client.openResponse(claimedGzip) }
    }

    // ---- filter ----

    private fun wireFilter(
        keys: String = "1:${Base64.getEncoder().encodeToString(HEX.parseHex(SERVER_PRIV_HEX))}",
        mode: String = "required",
    ) = WireCryptoFilter(keys, WireCrypto.DEFAULT_MAX_DECOMPRESSED_BYTES, mode, 5L * 1024 * 1024)

    /** 内层 servlet 回显看到的 content-type / body，便于断言解密包装；status 可指定。 */
    private fun runFilter(filter: WireCryptoFilter, req: MockHttpServletRequest, status: Int = 200): MockHttpServletResponse {
        val res = MockHttpServletResponse()
        val handler = object : HttpServlet() {
            override fun service(rq: jakarta.servlet.ServletRequest, rs: jakarta.servlet.ServletResponse) {
                val r = rq as HttpServletRequest
                (rs as HttpServletResponse).status = status
                rs.contentType = "application/json"
                rs.writer.write("ct=${r.contentType};hdr=${r.getHeader("Content-Type")};body=${String(r.inputStream.readAllBytes())}")
            }
        }
        filter.doFilter(req, res, MockFilterChain(handler))
        return res
    }

    @Test
    fun `filter decrypts request, encrypts response, keeps status`() {
        val client = TestClient(crypto.publicKey(1)!!, kid = 1)
        val req = MockHttpServletRequest("POST", "/api/customer/core/m_demo_todo_getById").apply {
            addHeader(RequestHeaders.WIREP_VERSION, "2")
            contentType = "application/octet-stream"
            setContent(client.sealRequest("""{"q":1}""".toByteArray(), ts = System.currentTimeMillis()))
        }
        val res = runFilter(wireFilter(), req, status = 401)
        assertThat(res.status).isEqualTo(401)
        assertThat(res.contentType).isEqualTo("application/octet-stream")
        assertThat(res.getHeader(RequestHeaders.WIREP_VERSION)).isEqualTo("2")
        assertThat(client.openResponse(res.contentAsByteArray).toString(Charsets.UTF_8))
            .isEqualTo("ct=application/json;hdr=application/json;body=${"{\"q\":1}"}")
    }

    @Test
    fun `filter bad payload returns plain 400003, empty config fails loud, plaintext rejected when required`() {
        val bad = runFilter(
            wireFilter(),
            MockHttpServletRequest("POST", "/api/customer/core/m_demo_todo_deleteOne").apply {
                addHeader(RequestHeaders.WIREP_VERSION, "2"); contentType = "application/octet-stream"; setContent(ByteArray(80))
            },
        )
        assertThat(bad.status).isEqualTo(400)
        assertThat(bad.contentAsString).isEqualTo("""{"code":"400003","msg":"bad encrypted payload","data":null}""")
        assertThat(bad.getHeader(RequestHeaders.WIREP_VERSION)).isNull()

        // 未配 key：required 模式下 /api/** 请求全部 400003（配置错误大声失败，无明文降级）
        val noKeys = runFilter(
            WireCryptoFilter("", WireCrypto.DEFAULT_MAX_DECOMPRESSED_BYTES, "required", 5L * 1024 * 1024),
            MockHttpServletRequest("POST", "/api/customer/core/m_demo_todo_deleteOne").apply {
                addHeader(RequestHeaders.WIREP_VERSION, "2"); contentType = "application/octet-stream"
                setContent(TestClient(crypto.publicKey(1)!!, kid = 1).sealRequest("{}".toByteArray(), ts = System.currentTimeMillis()))
            },
        )
        assertThat(noKeys.status).isEqualTo(400)
        assertThat(noKeys.contentAsString).isEqualTo("""{"code":"400003","msg":"bad encrypted payload","data":null}""")

        // required 模式：/api/** 明文请求 → 400 400004（无明文降级）
        val plain = runFilter(
            wireFilter(),
            MockHttpServletRequest("POST", "/api/customer/core/m_demo_todo_deleteOne").apply {
                contentType = "application/json"; setContent("{}".toByteArray())
            },
        )
        assertThat(plain.status).isEqualTo(400)
        assertThat(plain.contentAsString).isEqualTo("""{"code":"400004","msg":"wire encryption required","data":null}""")

        // 请求 body 全局上限：Content-Length 超限 → 400 400000（明文/密文统一，读取 body 之前拦截）
        val tooLarge = runFilter(
            WireCryptoFilter(
                "1:${Base64.getEncoder().encodeToString(HEX.parseHex(SERVER_PRIV_HEX))}",
                WireCrypto.DEFAULT_MAX_DECOMPRESSED_BYTES, "required", 64L,
            ),
            MockHttpServletRequest("POST", "/api/customer/core/m_demo_todo_deleteOne").apply {
                addHeader(RequestHeaders.WIREP_VERSION, "2"); contentType = "application/octet-stream"
                setContent(ByteArray(100))
            },
        )
        assertThat(tooLarge.status).isEqualTo(400)
        assertThat(tooLarge.contentAsString).isEqualTo("""{"code":"400000","msg":"request body too large","data":null}""")

        // required 模式：x-wirep-version 版本不符 → 400 400004（无版本协商）
        val wrongVer = runFilter(
            wireFilter(),
            MockHttpServletRequest("POST", "/api/customer/core/m_demo_todo_deleteOne").apply {
                addHeader(RequestHeaders.WIREP_VERSION, "1"); contentType = "application/octet-stream"; setContent(ByteArray(80))
            },
        )
        assertThat(wrongVer.status).isEqualTo(400)
        assertThat(wrongVer.contentAsString).isEqualTo("""{"code":"400004","msg":"unsupported x-wirep-version: 1","data":null}""")

        // required 模式：旧头名 x-proto-version 不识别；octet-stream 内容按密文处理 → 解密失败 400003
        val oldName = runFilter(
            wireFilter(),
            MockHttpServletRequest("POST", "/api/customer/core/m_demo_todo_deleteOne").apply {
                addHeader("x-proto-version", "2"); contentType = "application/octet-stream"; setContent(ByteArray(80))
            },
        )
        assertThat(oldName.status).isEqualTo(400)
        assertThat(oldName.contentAsString).isEqualTo("""{"code":"400003","msg":"bad encrypted payload","data":null}""")
    }

    @Test
    fun `optional mode passes plaintext through on api paths`() {
        val plain = runFilter(
            wireFilter(mode = "optional"),
            MockHttpServletRequest("POST", "/api/customer/core/m_demo_todo_deleteOne").apply {
                contentType = "application/json"; setContent("{}".toByteArray())
            },
        )
        assertThat(plain.contentAsString).isEqualTo("ct=application/json;hdr=application/json;body={}")
        assertThat(plain.getHeader(RequestHeaders.WIREP_VERSION)).isNull()

        // optional 模式：信封外路径（webhook 等）即便 required 也原样放行
        val outside = runFilter(
            wireFilter(),
            MockHttpServletRequest("POST", "/webhook/r2").apply { contentType = "application/json"; setContent("{}".toByteArray()) },
        )
        assertThat(outside.contentAsString).isEqualTo("ct=application/json;hdr=application/json;body={}")
    }

    /**
     * 防御性行为固化：v3 头但空 body（无 setContent）→ 明文 400 + 400003。
     * 无 body 请求没有密钥材料（resKey 派生自请求内 enc），不可能"跳过解密、仍加密响应"；
     * 此测试防止未来被改成那种走不通的路。
     */
    @Test
    fun `filter encrypted header with empty body returns plain 400003`() {
        val res = runFilter(wireFilter(), MockHttpServletRequest("POST", "/api/customer/core/m_demo_todo_deleteOne").apply {
            addHeader(RequestHeaders.WIREP_VERSION, "2"); contentType = "application/octet-stream"
        })
        assertThat(res.status).isEqualTo(400)
        assertThat(res.contentAsString).isEqualTo("""{"code":"400003","msg":"bad encrypted payload","data":null}""")
        assertThat(res.getHeader(RequestHeaders.WIREP_VERSION)).isNull()
    }

    @Test
    fun `filter clock skew over 5min is warn-only, request still processed`() {
        val client = TestClient(crypto.publicKey(1)!!, kid = 1)
        val req = MockHttpServletRequest("POST", "/api/customer/core/m_demo_todo_getById").apply {
            addHeader(RequestHeaders.WIREP_VERSION, "2")
            contentType = "application/octet-stream"
            // ts = 1 小时前（> 5min 偏差）
            setContent(client.sealRequest("""{"q":1}""".toByteArray(), ts = System.currentTimeMillis() - 3_600_000L))
        }
        val res = runFilter(wireFilter(), req)
        assertThat(res.status).isEqualTo(200)
        assertThat(res.contentType).isEqualTo("application/octet-stream")
        assertThat(client.openResponse(res.contentAsByteArray).toString(Charsets.UTF_8))
            .contains("""{"q":1}""")
    }

    companion object {
        val HEX = HexFormat.of()
        const val HEADER_LEN = 35 // 与 WireCrypto 头长对齐（ver‖kid‖enc‖flags）

        /** 固定服务端 key（openssl genpkey -algorithm X25519 生成；dev key 与 application-local.yml 同源）。 */
        private const val SERVER_PRIV_HEX = "3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f"
        /** 该私钥的 X25519 公钥（openssl pkey -pubout 等价；与 BC 推导交叉锁定）。 */
        private const val SERVER_PUB_HEX = "8855b39f1b92789433851a5ce8348487ec0cf7dd7777b8b9c2673d6994de6745"
    }
}

private fun ByteArray.toHex() = HexFormat.of().formatHex(this)

/** 独立实现的"客户端"：BC HPKE 封装请求 + JDK AES-GCM 解响应（响应 resKey 用客户端侧 Export 独立推导）。 */
private class TestClient(private val serverPub: ByteArray, private val kid: Int) {
    private val hpke = HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_AES_GCM256)
    private val eph: AsymmetricCipherKeyPair = hpke.generatePrivateKey()

    /** 每次 sealRequest 新建独立 sender context（seq 从 0）；BC base-mode context 每 seal 推进 seq，
     *  跨调用复用会让 nonce 漂移，与 wire v3 每请求独立 ephemeral 语义不符。resKey 亦从新 context 导出。 */
    private fun freshSender(): HPKEContextWithEncapsulation =
        hpke.setupBaseS(hpke.deserializePublicKey(serverPub), "ifmix-wire-v2".toByteArray(), eph)

    /** 响应 resKey：HPKE-Export(context, "ifmix-wire-v2-res", 32)。新 context 的 Export 与任意已用 seq 的 context 相同（导出 secret 只依赖 shared secret）。 */
    private val resKey: ByteArray = freshSender().export("ifmix-wire-v2-res".toByteArray(), 32)
    val enc: ByteArray = freshSender().encapsulation

    /** ver|kid|enc|flags|HPKE-Seal(aad=前35B, pt)；[inner] 供无 ts 等边界用例。每次独立 sender context。 */
    fun sealRequest(
        body: ByteArray,
        ts: Long = 42L,
        inner: ByteArray? = null,
    ): ByteArray {
        val cws = freshSender()
        val pt = inner ?: ByteBuffer.allocate(8).putLong(ts).array() + body
        val compressed = pt.size > GZIP_THRESHOLD
        val flags = if (compressed) 1 else 0
        val payload = if (compressed) pt.gzip() else pt
        val header = byteArrayOf(WireCrypto.VERSION.toByte(), kid.toByte()) + cws.encapsulation + byteArrayOf(flags.toByte())
        // 请求 AAD = 前 35B（ver‖kid‖enc‖flags）
        val sealed = cws.seal(header, payload)
        return header + sealed
    }

    /** 校验 flags（未知位即失败）→ AES-GCM(resKey, nonce, aad=enc‖flags) → bit0 时 gunzip。 */
    fun openResponse(wire: ByteArray): ByteArray {
        val flags = wire[0].toInt() and 0xff
        check(flags and 0b1111_1110 == 0) { "unknown flag bits: $flags" }
        val nonce = wire.copyOfRange(1, 13)
        val aad = enc + byteArrayOf(flags.toByte())
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(resKey, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(wire.copyOfRange(13, wire.size))
        }
        return if (flags and 1 == 1) GZIPInputStream(java.io.ByteArrayInputStream(plain)).readAllBytes() else plain
    }
}

private const val GZIP_THRESHOLD = 4096

private fun ByteArray.gzip(): ByteArray = ByteArrayOutputStream().use { buf ->
    java.util.zip.GZIPOutputStream(buf).use { it.write(this) }
    buf.toByteArray()
}
