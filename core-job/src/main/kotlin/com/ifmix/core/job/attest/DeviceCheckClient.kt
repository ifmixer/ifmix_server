package com.ifmix.core.job.attest

import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.JWTClaimsSet
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64
import com.ifmix.core.job.attest.DeviceCheckClient.CallResult

/**
 * Apple DeviceCheck「attest_data」客户端（规格 §5.8 fraud metric 刷新）。
 *
 * 用 per-project `deviceCheckKeyId` + `deviceCheckPrivateKey`（.p8，ES256）签 ES256 JWT，
 * POST 到按 `ios.env` 选 host 的端点，解析响应 `bit0` / `bit1`（§2 决策 2：响应不含新 receipt、
 * 无「下次允许刷新」字段；fraud_metric = bit0*2 + bit1，0..3）。
 *
 * host（§10.4 待核实项，按 Apple 官方域名写法，可配）：
 *  - production：`https://api.devicecheck.apple.com/v1/attest_data`
 *  - development：`https://api.development.devicecheck.apple.com/v1/attest_data`
 *
 * 判定口径：
 *  - 2xx 且解析出 bit0/bit1 → [CallResult.Success]；
 *  - 429 / 5xx / 网络异常 / 私钥解析失败 → [CallResult.TransientError]（指数退避下轮重试）；
 *  - deviceCheck* 配置缺失 → [CallResult.MissingConfig]（决策 4：跳过 + 日志，不报错）。
 */
interface DeviceCheckClient {
    data class Bits(val bit0: Int, val bit1: Int)

    sealed interface CallResult {
        data class Success(val bits: Bits) : CallResult
        object MissingConfig : CallResult
        data class TransientError(val status: Int?, val message: String) : CallResult
    }

    /** 刷新一次 fraud metric。[ios] 缺 deviceCheck* 时返回 [CallResult.MissingConfig]。 */
    fun refresh(ios: AppAttestIosConfig, receiptBytes: ByteArray): CallResult
}

@Component
class DeviceCheckClientImpl(
    @Value("\${app.attest.devicecheck-timeout-ms:15000}") private val timeoutMs: Long,
) : DeviceCheckClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build()

    override fun refresh(ios: AppAttestIosConfig, receiptBytes: ByteArray): CallResult {
        val keyId = ios.deviceCheckKeyId
        val p8 = ios.deviceCheckPrivateKey
        val env = ios.env
        if (keyId.isNullOrBlank() || p8.isNullOrBlank() || env.isNullOrBlank()) {
            log.info("[attest-refresh] deviceCheck 配置缺失，跳过. env={}", env)
            return CallResult.MissingConfig
        }
        val priv = try {
            parseP8(p8)
        } catch (e: Exception) {
            log.warn("[attest-refresh] .p8 私钥解析失败. cause={}", e.javaClass.simpleName)
            return CallResult.TransientError(null, "p8-parse-failed")
        }
        val receiptJson = mapper.writeValueAsString(mapOf("receipt" to Base64.getEncoder().encodeToString(receiptBytes)))
        val jwt = signEs256(priv, keyId, ios.teamId ?: "", receiptJson)
            ?: return CallResult.TransientError(null, "jwt-sign-failed")

        val host = if (env.equals("production", ignoreCase = true)) {
            "https://api.devicecheck.apple.com/v1/attest_data"
        } else {
            "https://api.development.devicecheck.apple.com/v1/attest_data"
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create(host))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $jwt")
            .POST(HttpRequest.BodyPublishers.ofString(receiptJson))
            .build()
        return try {
            val resp = http.send(request, HttpResponse.BodyHandlers.ofString())
            when {
                resp.statusCode() in 200..299 -> parseBits(resp.body())
                resp.statusCode() == 429 || resp.statusCode() >= 500 -> {
                    log.warn("[attest-refresh] DeviceCheck 429/5xx. status={}", resp.statusCode())
                    CallResult.TransientError(resp.statusCode(), "status=${resp.statusCode()}")
                }
                else -> {
                    log.warn("[attest-refresh] DeviceCheck 4xx（非 429）. status={}", resp.statusCode())
                    CallResult.TransientError(resp.statusCode(), "status=${resp.statusCode()}")
                }
            }
        } catch (e: Exception) {
            log.warn("[attest-refresh] 网络错误. cause={}", e.javaClass.simpleName)
            CallResult.TransientError(null, e.javaClass.simpleName)
        }
    }

    private fun parseBits(raw: String): CallResult {
        val bits = try {
            val tree = mapper.readTree(raw)
            DeviceCheckClient.Bits(
                bit0 = ((tree.get("bit0")?.asInt(0) ?: 0).coerceIn(0, 1)),
                bit1 = ((tree.get("bit1")?.asInt(0) ?: 0).coerceIn(0, 1)),
            )
        } catch (e: Exception) {
            log.warn("[attest-refresh] 响应解析失败. body={}", raw.take(200))
            return CallResult.TransientError(null, "bad-response")
        }
        return CallResult.Success(bits)
    }

    private fun signEs256(priv: PrivateKey, keyId: String, teamId: String, receiptJson: String): String? = try {
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .keyID(keyId)
            .type(JOSEObjectType("application/x-apple-deviced-check-authentication+jwt"))
            .build()
        val claims = JWTClaimsSet.parse(mapOf("iss" to teamId))
        val jws = SignedJWT(header, claims)
        // ECDSASigner(PrivateKey, Curve) 由 nimbus 自行推导公钥（内部用 ECKeyFactory）。
        jws.sign(ECDSASigner(priv, Curve.P_256))
        jws.serialize()
    } catch (e: Exception) {
        log.warn("[attest-refresh] JWT 签名失败. cause={}", e.javaClass.simpleName)
        null
    }

    /** .p8（PKCS#8 PEM，EC P-256）→ PrivateKey。 */
    private fun parseP8(pem: String): PrivateKey {
        val der = pem.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("-----") }
            .joinToString("").let { Base64.getDecoder().decode(it) }
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
    }
}
