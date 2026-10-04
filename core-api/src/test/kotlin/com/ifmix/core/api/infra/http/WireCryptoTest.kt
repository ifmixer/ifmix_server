package com.ifmix.core.api.infra.http

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.interfaces.XECPublicKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KDF
import javax.crypto.KeyAgreement
import javax.crypto.spec.HKDFParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** v2 加解密往返 + filter 行为。客户端加密逻辑在此用 JDK 重写一遍（独立于被测实现），另有 JS 生成的互通向量。 */
class WireCryptoTest {

    private val devPriv = "mGZm9So8/JYSHc2kf/zNLoEIZHj6pBR87jblNxYLwng="
    private val devPub = "XAmAvDX80XNHA1xQY9y/MR95uGDUbU4bw85GTCyPVGA="
    private val crypto = WireCrypto.parse("1:$devPriv, 7:${Base64.getEncoder().encodeToString(ByteArray(32) { 3 })}")

    /** 测试侧"客户端"：返回 (请求 payload, 解响应函数)。 */
    private fun clientSeal(serverPub: ByteArray, kid: Int, body: ByteArray, ts: Long = System.currentTimeMillis()): Pair<ByteArray, (ByteArray) -> ByteArray> {
        val kp = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        val ephPub = (kp.public as XECPublicKey).u.toByteArray().reversedArray().copyOf(32) // u 大端 → 小端
        val shared = KeyAgreement.getInstance("X25519").run {
            init(kp.private)
            doPhase(java.security.KeyFactory.getInstance("X25519").generatePublic(
                java.security.spec.X509EncodedKeySpec(java.util.HexFormat.of().parseHex("302a300506032b656e032100") + serverPub)), true)
            generateSecret()
        }
        val okm = KDF.getInstance("HKDF-SHA256").deriveData(
            HKDFParameterSpec.ofExtract().addIKM(shared).addSalt(ephPub + serverPub).thenExpand("ifmix-wire-v2".toByteArray(), 64))
        val header = byteArrayOf(2, kid.toByte()) + ephPub
        val nonce = ByteArray(12) { it.toByte() }
        val ct = chacha(Cipher.ENCRYPT_MODE, okm.copyOfRange(0, 32), nonce, header, ByteBuffer.allocate(8).putLong(ts).array() + body)
        val open = { res: ByteArray -> chacha(Cipher.DECRYPT_MODE, okm.copyOfRange(32, 64), res.copyOfRange(0, 12), ephPub, res.copyOfRange(12, res.size)) }
        return header + nonce + ct to open
    }

    private fun chacha(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray) =
        Cipher.getInstance("ChaCha20-Poly1305").run {
            init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce)); updateAAD(aad); doFinal(input)
        }

    @Test
    fun `derived public key matches openssl and round trip works`() {
        assertThat(Base64.getEncoder().encodeToString(crypto.publicKey(1))).isEqualTo(devPub)
        val (payload, openRes) = clientSeal(crypto.publicKey(1)!!, 1, "{\"a\":1}".toByteArray(), ts = 42)
        val opened = crypto.open(payload)
        assertThat(String(opened.body)).isEqualTo("{\"a\":1}")
        assertThat(opened.clientTsMs).isEqualTo(42L)
        assertThat(String(openRes(opened.seal("resp".toByteArray())))).isEqualTo("resp")
    }

    @Test
    fun `multi kid, unknown kid, tampering all handled`() {
        val (p7, _) = clientSeal(crypto.publicKey(7)!!, 7, "x".toByteArray())
        assertThat(String(crypto.open(p7).body)).isEqualTo("x")
        val (p1, _) = clientSeal(crypto.publicKey(1)!!, 9, "x".toByteArray())
        assertThrows<WireCryptoException> { crypto.open(p1) }
        val (good, _) = clientSeal(crypto.publicKey(1)!!, 1, "x".toByteArray())
        good[good.size - 1] = (good.last() + 1).toByte()
        assertThrows<WireCryptoException> { crypto.open(good) }
        assertThrows<WireCryptoException> { crypto.open(ByteArray(10)) }
        assertThrows<WireCryptoException> { WireCrypto.parse("").open(good) }
    }

    /** 由客户端 JS 实现（apps/shared/src/api/wireCrypto.ts，固定 eph/nonce/ts）生成，保证两端逐字节互通。 */
    @Test
    fun `decrypts js client vector`() {
        val opened = crypto.open(Base64.getDecoder().decode(JS_VECTOR))
        assertThat(String(opened.body)).isEqualTo("""{"query":"","variables":{"x":"中文"}}""")
        assertThat(opened.clientTsMs).isEqualTo(1_700_000_000_000L)
    }

    private val filter = WireCryptoFilter("1:$devPriv")

    private fun runFilter(req: MockHttpServletRequest, status: Int = 200): MockHttpServletResponse {
        val res = MockHttpServletResponse()
        val handler = object : jakarta.servlet.http.HttpServlet() {
            override fun service(rq: jakarta.servlet.ServletRequest, rs: jakarta.servlet.ServletResponse) {
                val r = rq as jakarta.servlet.http.HttpServletRequest
                (rs as jakarta.servlet.http.HttpServletResponse).status = status
                rs.contentType = "application/json"
                rs.writer.write("ct=${r.contentType};hdr=${r.getHeader("Content-Type")};body=${String(r.inputStream.readAllBytes())}")
            }
        }
        filter.doFilter(req, res, MockFilterChain(handler))
        return res
    }

    @Test
    fun `filter decrypts request, encrypts response, keeps status`() {
        val (payload, openRes) = clientSeal(crypto.publicKey(1)!!, 1, "{\"q\":1}".toByteArray())
        val req = MockHttpServletRequest("POST", "/customer/core/greq/m_x").apply {
            addHeader(RequestHeaders.PROTO_VERSION, "2"); contentType = "application/octet-stream"; setContent(payload)
        }
        val res = runFilter(req, status = 401)
        assertThat(res.status).isEqualTo(401)
        assertThat(res.contentType).isEqualTo("application/octet-stream")
        assertThat(res.getHeader(RequestHeaders.PROTO_VERSION)).isEqualTo("2")
        assertThat(String(openRes(res.contentAsByteArray))).isEqualTo("ct=application/json;hdr=application/json;body={\"q\":1}")
    }

    @Test
    fun `filter bad payload returns plain 400003, v1 passes through untouched`() {
        val bad = runFilter(MockHttpServletRequest("POST", "/x").apply { addHeader(RequestHeaders.PROTO_VERSION, "2"); setContent(ByteArray(80)) })
        assertThat(bad.status).isEqualTo(400)
        assertThat(bad.contentAsString).isEqualTo("""{"code":"400003","msg":"bad encrypted payload","data":null}""")

        val plain = runFilter(MockHttpServletRequest("POST", "/x").apply { contentType = "application/json"; setContent("{}".toByteArray()) })
        assertThat(plain.contentAsString).isEqualTo("ct=application/json;hdr=application/json;body={}")
        assertThat(plain.getHeader(RequestHeaders.PROTO_VERSION)).isNull()
    }

    companion object {
        const val JS_VECTOR = "AgFQphQJsd3QMl6bFrcA5xnpdywHAAsb13hukHxlPSBJXQkJCQkJCQkJCQkJCctF8L7in8OECR3cAM6hf9FyZEC/cc5lTtymn/xeAYzjTohKmDsSj9LI7s6uot4k0TciKPpJguUCwvTCIkQ7ng=="
    }
}
