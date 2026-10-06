package com.ifmix.core.api.infra.http

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isNull
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.spec.XECPrivateKeySpec
import java.util.Base64
import java.util.HexFormat
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.KDF
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.HKDFParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v2 加解密往返 + filter 行为。测试侧"客户端"用 JDK crypto 独立实现线格式（不复用 WireCrypto 的代码）；
 * 固定向量由客户端 JS 实现（antique: apps/shared/src/api/wireCrypto.ts，固定 eph/nonce/ts）生成，见设计 §7。
 */
class WireCryptoTest {

    private val crypto = WireCrypto(
        mapOf(
            1 to HF.parseHex(SERVER_PRIV_HEX),
            7 to ByteArray(32) { 3 },
        ),
    )

    @Test
    fun `opens js client fixed vector`() {
        val opened = crypto.open(HF.parseHex(REQ_VECTOR_HEX))
        assertThat(opened.body.toString(Charsets.UTF_8)).isEqualTo(REQ_JSON)
        assertThat(opened.clientTsMs).isEqualTo(1_700_000_000_000L)
        assertThat(opened.kid).isEqualTo(1)
    }

    @Test
    fun `derives public key matching externally generated keypairs`() {
        assertThat(crypto.publicKey(1)!!.toHex()).isEqualTo(SERVER_PUB_HEX)
        // openssl genpkey 生成、非本实现产物的 keypair：验证推导等价 openssl pkey -pubout
        val dev = WireCrypto.parse("1:$DEV_PRIV_B64")
        assertThat(dev.publicKey(1)!!.toHex()).isEqualTo(DEV_PUB_HEX)
    }

    @Test
    fun `round trips with independent client impl`() {
        val client = TestClient(crypto.publicKey(1)!!, kid = 1)
        val body = """{"query":"","variables":{"x":"中文"}}"""
        val opened = crypto.open(client.sealRequest(body.toByteArray(), ts = 42))
        assertThat(opened.body.toString(Charsets.UTF_8)).isEqualTo(body)
        assertThat(opened.clientTsMs).isEqualTo(42L)

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
    fun `multi kid, unknown kid, tampering, missing ts, empty config`() {
        val c7 = TestClient(crypto.publicKey(7)!!, kid = 7)
        assertThat(crypto.open(c7.sealRequest("x".toByteArray())).body.toString(Charsets.UTF_8)).isEqualTo("x")

        val unknownKid = c7.sealRequest("x".toByteArray()).also { it[1] = 9 }
        assertThrows<WireCryptoException> { crypto.open(unknownKid) }

        val tampered = c7.sealRequest("x".toByteArray()).also { it[it.size - 1] = (it.last() + 1).toByte() }
        assertThrows<WireCryptoException> { crypto.open(tampered) }

        val badVer = c7.sealRequest("x".toByteArray()).also { it[0] = 3 }
        assertThrows<WireCryptoException> { crypto.open(badVer) }
        assertThrows<WireCryptoException> { crypto.open(ByteArray(10)) }

        // GCM 合法但 inner 无 8 字节 ts
        val noTs = TestClient(crypto.publicKey(1)!!, kid = 1).sealRequest(ByteArray(0), inner = ByteArray(0))
        assertThrows<WireCryptoException> { crypto.open(noTs) }

        // 低阶（小序）点 ephPub：JDK Montgomery ladder 不拒绝、照算 shared，必须靠黑名单拒。
        // 全零 32B（x=0）与 2^255-1（order-2 点，两种符号位编码）各一例；
        // 其余 8 个编码同逻辑（5 个 x × 2 符号位，见 WireCrypto.LOW_ORDER_EPH_PUBS）。
        for (dangerous in listOf(
            "0000000000000000000000000000000000000000000000000000000000000000",
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        )) {
            val payload = byteArrayOf(2, 1) + HF.parseHex(dangerous) + ByteArray(12 + 16)
            assertThrows<WireCryptoException> { crypto.open(payload) }
        }

        val empty = WireCrypto.parse("")
        assertThat(empty.isEnabled).isEqualTo(false)
        assertThat(empty.publicKey(1)).isNull()
        assertThrows<WireCryptoException> { empty.open(tampered) }
        // 配置错误与 payload 错误区分：前者启动期 fail-fast
        assertThrows<IllegalArgumentException> { WireCrypto.parse("bad") }
        assertThrows<IllegalArgumentException> { WireCrypto.parse("0:${Base64.getEncoder().encodeToString(ByteArray(32))}") }
        assertThrows<IllegalArgumentException> { WireCrypto.parse("1:${Base64.getEncoder().encodeToString(ByteArray(31))}") }
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

    private fun v2Filter() = WireCryptoFilter("1:${Base64.getEncoder().encodeToString(HF.parseHex(SERVER_PRIV_HEX))}")

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
        val req = MockHttpServletRequest("POST", "/customer/core/greq/m_x").apply {
            addHeader(RequestHeaders.PROTO_VERSION, "2")
            contentType = "application/octet-stream"
            setContent(client.sealRequest("""{"q":1}""".toByteArray(), ts = System.currentTimeMillis()))
        }
        val res = runFilter(v2Filter(), req, status = 401)
        assertThat(res.status).isEqualTo(401)
        assertThat(res.contentType).isEqualTo("application/octet-stream")
        assertThat(res.getHeader(RequestHeaders.PROTO_VERSION)).isEqualTo("2")
        assertThat(client.openResponse(res.contentAsByteArray).toString(Charsets.UTF_8))
            .isEqualTo("ct=application/json;hdr=application/json;body=${"{\"q\":1}"}")
    }

    @Test
    fun `filter bad payload returns plain 400003, empty config too, v1 passes through untouched`() {
        val bad = runFilter(v2Filter(), MockHttpServletRequest("POST", "/x").apply {
            addHeader(RequestHeaders.PROTO_VERSION, "2"); contentType = "application/octet-stream"; setContent(ByteArray(80))
        })
        assertThat(bad.status).isEqualTo(400)
        assertThat(bad.contentAsString).isEqualTo("""{"code":"400003","msg":"bad encrypted payload","data":null}""")
        assertThat(bad.getHeader(RequestHeaders.PROTO_VERSION)).isNull()

        // 未配 key：合法 v2 请求同样 400003（isEnabled=false → 客户端降级明文）
        val noKeys = runFilter(WireCryptoFilter(""), MockHttpServletRequest("POST", "/x").apply {
            addHeader(RequestHeaders.PROTO_VERSION, "2"); contentType = "application/octet-stream"
            setContent(TestClient(crypto.publicKey(1)!!, kid = 1).sealRequest("{}".toByteArray(), ts = System.currentTimeMillis()))
        })
        assertThat(noKeys.status).isEqualTo(400)
        assertThat(noKeys.contentAsString).isEqualTo("""{"code":"400003","msg":"bad encrypted payload","data":null}""")

        val plain = runFilter(v2Filter(), MockHttpServletRequest("POST", "/x").apply { contentType = "application/json"; setContent("{}".toByteArray()) })
        assertThat(plain.contentAsString).isEqualTo("ct=application/json;hdr=application/json;body={}")
        assertThat(plain.getHeader(RequestHeaders.PROTO_VERSION)).isNull()

        val v1 = runFilter(v2Filter(), MockHttpServletRequest("POST", "/x").apply {
            addHeader(RequestHeaders.PROTO_VERSION, "1"); contentType = "application/json"; setContent("{}".toByteArray())
        })
        assertThat(v1.contentAsString).isEqualTo("ct=application/json;hdr=application/json;body={}")
    }

    /**
     * 防御性行为固化：v2 头但空 body（无 setContent）→ 明文 400 + 400003。
     * 无 body 请求没有密钥材料（resKey 派生自请求内 ephPub），不可能"跳过解密、仍加密响应"；
     * 此测试防止未来被改成那种走不通的路。
     */
    @Test
    fun `filter v2 header with empty body returns plain 400003`() {
        val res = runFilter(v2Filter(), MockHttpServletRequest("POST", "/x").apply {
            addHeader(RequestHeaders.PROTO_VERSION, "2"); contentType = "application/octet-stream"
        })
        assertThat(res.status).isEqualTo(400)
        assertThat(res.contentAsString).isEqualTo("""{"code":"400003","msg":"bad encrypted payload","data":null}""")
        assertThat(res.getHeader(RequestHeaders.PROTO_VERSION)).isNull()
    }

    @Test
    fun `filter clock skew over 5min is warn-only, request still processed`() {
        val client = TestClient(crypto.publicKey(1)!!, kid = 1)
        val req = MockHttpServletRequest("POST", "/customer/core/greq/m_x").apply {
            addHeader(RequestHeaders.PROTO_VERSION, "2")
            contentType = "application/octet-stream"
            // ts = 1 小时前（> 5min 偏差）
            setContent(client.sealRequest("""{"q":1}""".toByteArray(), ts = System.currentTimeMillis() - 3_600_000L))
        }
        val res = runFilter(v2Filter(), req)
        assertThat(res.status).isEqualTo(200)
        assertThat(res.contentType).isEqualTo("application/octet-stream")
        assertThat(client.openResponse(res.contentAsByteArray).toString(Charsets.UTF_8))
            .contains("""{"q":1}""")
    }

    companion object {
        private val HF = HexFormat.of()

        /** 跨语言固定向量：独立 eph/server keypair、固定 nonce/ts，由客户端 JS 实现生成（设计 §7）。 */
        private const val SERVER_PRIV_HEX = "3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f"
        private const val SERVER_PUB_HEX = "8855b39f1b92789433851a5ce8348487ec0cf7dd7777b8b9c2673d6994de6745"
        private const val REQ_VECTOR_HEX =
            "02015fef13fc76023a9ee6ded987b6aa93958cdc2097ef9fc845d5319c9ca100d35e" +
                "abababababababababababab" +
                "512e68c7e72e1f1defb1fa4c293ebb6d07060d27d6ff9d14b52a7af99717dd28ec408b04d66b5ed27dc3c43e4ce3ed30b8fd4a9e32e7959ee5eea34605ff33ddfe25cb270e273c8515f7cf87f8988148e6f9d1daed76378066fb47f6516abc454d431dc745aaf02b04e88a687f598c"
        private const val REQ_JSON =
            """{"query": "mutation{m_install_createInstall(input:{}){installToken}}", "variables": {}}"""

        /** openssl genpkey -algorithm X25519 生成（dev key：application-local.yml 与客户端 local/dev 预设同源）。 */
        private const val DEV_PRIV_B64 = "V64XjEMXAoqme3StrLwe1+Yt1Z3/BwrJOUCnsuqx2lk="
        private const val DEV_PUB_HEX = "2d2e43125e756b7fdcf2336c857750e2fd3bbd48449583783342968d24caf904"
    }
}

private val X509_PREFIX = HexFormat.of().parseHex("302a300506032b656e032100")

private fun ByteArray.toHex(): String = HexFormat.of().formatHex(this)

private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray =
    Cipher.getInstance("AES/GCM/NoPadding").run {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        updateAAD(aad)
        doFinal(input)
    }

/** 独立实现的"客户端"：JDK crypto 组装/拆解线格式，不触碰 WireCrypto 的任何代码。 */
private class TestClient(serverPub: ByteArray, private val kid: Int) {
    private val kp = KeyPairGenerator.getInstance("X25519").generateKeyPair()
    private val ephPub = (kp.public as XECPublicKey).u.toByteArray().reversedArray().copyOf(32) // u 大端 → 小端 32B
    private val okm: ByteArray

    init {
        val ka = KeyAgreement.getInstance("X25519")
        ka.init(kp.private)
        ka.doPhase(KeyFactory.getInstance("X25519").generatePublic(X509EncodedKeySpec(X509_PREFIX + serverPub)), true)
        val shared = ka.generateSecret()
        okm = KDF.getInstance("HKDF-SHA256").deriveData(
            HKDFParameterSpec.ofExtract().addIKM(shared).addSalt(ephPub + serverPub).thenExpand("ifmix-wire-v2".toByteArray(), 64),
        )
    }

    /** ver|kid|ephPub|nonce|GCM(reqKey, nonce, ts(8 BE) ‖ body)；[inner] 供无 ts 等边界用例。 */
    fun sealRequest(
        body: ByteArray,
        ts: Long = 42L,
        nonce: ByteArray = ByteArray(12) { it.toByte() },
        inner: ByteArray? = null,
    ): ByteArray {
        val header = byteArrayOf(2, kid.toByte()) + ephPub
        val plain = inner ?: ByteBuffer.allocate(8).putLong(ts).array() + body
        return header + nonce + gcm(Cipher.ENCRYPT_MODE, okm.copyOfRange(0, 32), nonce, header, plain)
    }

    /** 校验 flags（未知位即失败）→ GCM → bit0 时 gunzip。 */
    fun openResponse(wire: ByteArray): ByteArray {
        val flags = wire[0].toInt() and 0xff
        check(flags and 0b1111_1110 == 0) { "unknown flag bits: $flags" }
        val plain = gcm(Cipher.DECRYPT_MODE, okm.copyOfRange(32, 64), wire.copyOfRange(1, 13), ephPub + byteArrayOf(wire[0]), wire.copyOfRange(13, wire.size))
        return if (flags and 1 == 1) GZIPInputStream(ByteArrayInputStream(plain)).readAllBytes() else plain
    }
}
