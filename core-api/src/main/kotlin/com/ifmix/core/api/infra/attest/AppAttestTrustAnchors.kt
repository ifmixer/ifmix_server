package com.ifmix.core.api.infra.attest

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import com.webauthn4j.anchor.KeyStoreTrustAnchorRepository
import com.webauthn4j.verifier.attestation.trustworthiness.certpath.CertPathTrustworthinessVerifier
import com.webauthn4j.verifier.attestation.trustworthiness.certpath.DefaultCertPathTrustworthinessVerifier
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale

/**
 * Apple App Attestation Root CA 信任锚（规格 §5.2）。
 *
 * - 从 classpath `attest/apple-app-attestation-root-ca.pem` 加载（PEM 单证书）；
 * - **启动时**校验 DER 编码证书的 SHA-256 指纹与代码常量一致，不一致直接抛异常（启动失败，
 *   防止资源文件被替换后静默信任错误链条）；
 * - 构造 WebAuthn4J 的 `KeyStoreTrustAnchorRepository` + `DefaultCertPathTrustworthinessVerifier`
 *   （**不使用** JVM 系统信任库，**不使用** Null verifier）。
 *
 * 资源缺失时的降级：不在启动期失败（避免无关接口被单模块拖垮），而是延迟到第一次验签时
 * 由 [certPathTrustworthinessVerifier] 抛出明确错误（`AppAttestVerifier` 把它映射为 UNAVAILABLE）。
 *
 * Apple 轮换 / 新增根证书时：更新资源文件与 [EXPECTED_SHA256] 常量，随版本发布。
 */
@Component
class AppAttestTrustAnchors(
    private val resourcePath: String = RESOURCE_PATH,
    private val expectedSha256: String = EXPECTED_SHA256,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val verifier: DefaultCertPathTrustworthinessVerifier?

    init {
        val pemBytes = javaClass.classLoader.getResourceAsStream(resourcePath)?.use { it.readBytes() }
        if (pemBytes == null) {
            log.error(
                "event=attest.trust_anchor_missing resource={} — App Attest 验签将不可用（第一次验签时报 UNAVAILABLE）",
                resourcePath,
            )
            verifier = null
        } else {
            val cert = parsePem(pemBytes)
            val fingerprint = sha256Hex(cert.encoded)
            if (!fingerprint.equals(expectedSha256, ignoreCase = true)) {
                // 指纹不一致 = 资源被替换或装错文件：按规格直接启动失败。
                throw IllegalStateException(
                    "Apple App Attestation Root CA fingerprint mismatch: expected=$expectedSha256 actual=$fingerprint " +
                        "(resource=$resourcePath)。证书资源与代码常量不一致，拒绝启动。",
                )
            }
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, null)
            keyStore.setCertificateEntry(ALIAS, cert)
            verifier = DefaultCertPathTrustworthinessVerifier(KeyStoreTrustAnchorRepository(keyStore))
            log.info(
                "event=attest.trust_anchor_loaded subject={} fingerprint={}",
                cert.subjectX500Principal.name,
                fingerprint,
            )
        }
    }

    /**
     * 证书路径可信校验器（WebAuthn4J `CertPathTrustworthinessVerifier`）。
     * 资源缺失时第一次调用抛 IllegalStateException（明确报错，延迟失败）。
     */
    fun certPathTrustworthinessVerifier(): CertPathTrustworthinessVerifier =
        verifier
            ?: throw IllegalStateException(
                "App Attest trust anchor unavailable: classpath resource '$resourcePath' is missing. " +
                    "Put the Apple App Attestation Root CA PEM at that path (see specs §5.2).",
            )

    private fun parsePem(pem: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(pem)) as X509Certificate

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(Locale.ROOT, it) }

    companion object {
        const val RESOURCE_PATH = "attest/apple-app-attestation-root-ca.pem"
        private const val ALIAS = "apple-app-attestation-root-ca"

        /**
         * 期望指纹：DER 编码证书字节（`Certificate.getEncoded()`）的 SHA-256。
         *
         * 来源：Apple 官方 Private PKI 页面 https://www.apple.com/certificateauthority/private
         * （下载链接 Apple_App_Attestation_Root_CA.pem），下载日期 2026-10-04。
         * 证书：CN=Apple App Attestation Root CA, O=Apple Inc.；有效期 2020-03-18 → 2045-03-15。
         * 复算：`openssl x509 -in apple-app-attestation-root-ca.pem -outform DER | shasum -a 256`
         */
        const val EXPECTED_SHA256 = "1cb9823ba28ba6ad2d33a006941de2ae4f513ef1d4e831b9f7e0fa7b6242c932"
    }
}
