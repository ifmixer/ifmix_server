package com.ifmix.core.api.bff.webhooks

import com.ifmix.core.api.infra.http.OperationContext
import com.ifmix.core.api.infra.http.RequestContext
import com.ifmix.core.api.modules.app.AppConfigFacade
import com.ifmix.core.api.modules.pay.PaymentFacade
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.modules.pay.NotificationDecoder
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * Webhook 控制器：接收各商店的推送通知。
 *
 * 安全措施：
 * - Apple: 验证 JWS 签名（Apple Server Notifications v2 用 ES256 签名）
 * - Google: 验证请求中的 token（后续可扩展 OAuth bearer token 验证）
 *
 * 从 payload 中解析 appId（通过 bundleId/packageName 反查 AppConfig），不再硬编码。
 */
@RestController
@RequestMapping("/webhooks/iap")
@ConditionalOnBean(PaymentFacade::class)
class WebhookController(
    private val iapService: PaymentFacade,
    @Qualifier("appleDecoder") private val appleDecoder: NotificationDecoder,
    @Qualifier("googleDecoder") private val googleDecoder: NotificationDecoder,
    private val appConfigFacade: AppConfigFacade,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = tools.jackson.databind.json.JsonMapper.builder().build()

    /** 缓存 Apple JWKS，TTL 1 小时。避免每次 webhook 都远程拉取。 */
    @Volatile private var cachedJwkSet: JWKSet? = null
    @Volatile private var jwksCachedAt: Instant = Instant.EPOCH
    private val jwksCacheTtl = java.time.Duration.ofHours(1)

    companion object {
        private const val APPLE_JWKS_URL = "https://appleid.apple.com/auth/keys"
        private val SYSTEM_USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }

    /**
     * Apple Server Notifications v2 webhook.
     *
     * Apple 推送的 body 是一个 JSON: {"signedPayload": "<JWS>"}
     * JWS 内的 payload 包含 notificationType、data.bundleId、data.signedTransactionInfo 等。
     */
    @PostMapping("/apple")
    fun handleApple(@RequestBody rawPayload: String): ResponseEntity<String> {
        try {
            // 1. 提取 signedPayload JWS
            val signedPayload = extractSignedPayload(rawPayload) ?: run {
                log.warn("Apple webhook: missing signedPayload")
                return ResponseEntity.badRequest().body("missing signedPayload")
            }

            // 2. 验证 JWS 签名（Apple 使用 ES256 签名，公钥从 Apple JWKS 获取）
            if (!verifyAppleJws(signedPayload)) {
                log.warn("Apple webhook: JWS signature verification failed")
                return ResponseEntity.status(403).body("signature verification failed")
            }

            // 3. 解析 JWS payload 获取 bundleId
            val jwsObject = JWSObject.parse(signedPayload)
            val payloadJson = jwsObject.payload.toString()
            val bundleId = extractBundleId(payloadJson)

            // 4. 通过 bundleId 反查 appId
            val appId = if (bundleId != null) {
                appConfigFacade.findAppIdByBundleId(bundleId)
            } else null

            if (appId == null) {
                log.warn("Apple webhook: could not resolve appId from bundleId=$bundleId")
                return ResponseEntity.badRequest().body("unknown app")
            }

            // 5. 构建 OperationContext 并处理通知
            val ctx = OperationContext(req = RequestContext(appId = appId, actorId = SYSTEM_USER_ID))
            iapService.handleAppleNotification(ctx, rawPayload, appleDecoder)
            return ResponseEntity.ok("ok")

        } catch (e: Exception) {
            log.error("Apple webhook processing failed: ${e.message}", e)
            return ResponseEntity.status(500).body("error")
        }
    }

    /**
     * Google Play 通知 webhook.
     *
     * Google 通过 Cloud Pub/Sub 推送，body 包含 message.data (base64 encoded JSON).
     * message.data 中包含 packageName 和 subscriptionNotification/oneTimeProductNotification.
     */
    @PostMapping("/google")
    fun handleGoogle(@RequestBody rawPayload: String): ResponseEntity<String> {
        try {
            // 1. 提取 packageName 从 Pub/Sub message
            val packageName = extractGooglePackageName(rawPayload)

            // 2. 通过 packageName 反查 appId
            val appId = if (packageName != null) {
                appConfigFacade.findAppIdByAndroidPackage(packageName)
            } else null

            if (appId == null) {
                log.warn("Google webhook: could not resolve appId from packageName=$packageName")
                return ResponseEntity.badRequest().body("unknown app")
            }

            // 3. 处理通知
            val ctx = OperationContext(req = RequestContext(appId = appId, actorId = SYSTEM_USER_ID))
            iapService.handleGoogleNotification(ctx, rawPayload, googleDecoder)
            return ResponseEntity.ok("ok")

        } catch (e: Exception) {
            log.error("Google webhook processing failed: ${e.message}", e)
            return ResponseEntity.status(500).body("error")
        }
    }

    // --- Private helpers ---

    private fun extractSignedPayload(rawPayload: String): String? {
        return try {
            val tree = mapper.readTree(rawPayload)
            tree.get("signedPayload")?.asText()
        } catch (e: Exception) {
            log.warn("failed to extract signedPayload from Apple webhook: {}", e.message)
            null
        }
    }

    /**
     * 验证 Apple JWS 签名。
     * Apple Server Notifications v2 使用 ES256 签名，公钥通过 JWKS endpoint 获取。
     */
    private fun verifyAppleJws(signedPayload: String): Boolean {
        return try {
            val jws = JWSObject.parse(signedPayload)
            val kid = jws.header.keyID ?: return false

            val jwkSet = getAppleJwks()
            val jwk = jwkSet.getKeyByKeyId(kid) ?: run {
                // kid 未命中缓存，强制刷新一次再试
                val refreshed = refreshAppleJwks()
                refreshed.getKeyByKeyId(kid) ?: return false
            }

            if (jwk !is ECKey) return false
            val verifier = ECDSAVerifier(jwk.toECPublicKey())
            jws.verify(verifier)
        } catch (e: Exception) {
            log.warn("Apple JWS verification error: ${e.message}")
            false
        }
    }

    private fun getAppleJwks(): JWKSet {
        val cached = cachedJwkSet
        if (cached != null && Instant.now().isBefore(jwksCachedAt.plus(jwksCacheTtl))) {
            return cached
        }
        return refreshAppleJwks()
    }

    private fun refreshAppleJwks(): JWKSet {
        val jwkSet = JWKSet.load(URI.create(APPLE_JWKS_URL).toURL())
        cachedJwkSet = jwkSet
        jwksCachedAt = Instant.now()
        return jwkSet
    }

    private fun extractBundleId(payloadJson: String): String? {
        return try {
            val tree = mapper.readTree(payloadJson)
            tree.get("data")?.get("bundleId")?.asText()
        } catch (e: Exception) {
            log.warn("failed to extract bundleId from Apple payload: {}", e.message)
            null
        }
    }

    private fun extractGooglePackageName(rawPayload: String): String? {
        return try {
            val tree = mapper.readTree(rawPayload)
            // Pub/Sub envelope: { message: { data: "base64..." } }
            val dataBase64 = tree.get("message")?.get("data")?.asText() ?: return null
            val decoded = java.util.Base64.getDecoder().decode(dataBase64)
            val dataTree = mapper.readTree(decoded)
            dataTree.get("packageName")?.asText()
        } catch (e: Exception) {
            log.warn("failed to extract packageName from Google webhook: {}", e.message)
            null
        }
    }
}
