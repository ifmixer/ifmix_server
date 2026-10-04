package com.ifmix.core.api.infra.attest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * AppAttestTrustAnchors 单测：
 * - 资源在 classpath 时：加载成功、指纹与代码常量一致、CN 含 "App Attestation"；
 * - 指纹不一致 → 启动失败（抛 IllegalStateException）；
 * - 资源缺失 → 不在启动期失败，第一次验签才报明确错误。
 */
class AppAttestTrustAnchorsTest {

    @Test
    fun `resource loads and pinned fingerprint matches`() {
        val anchors = AppAttestTrustAnchors()
        // 不抛异常即加载成功 + 指纹校验通过
        assertThat(anchors.certPathTrustworthinessVerifier()).isNotNull()
    }

    @Test
    fun `resource is the Apple App Attestation Root CA`() {
        val pem = javaClass.classLoader.getResourceAsStream(AppAttestTrustAnchors.RESOURCE_PATH)!!.use { it.readBytes() }
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(pem)) as X509Certificate
        assertThat(cert.subjectX500Principal.name).contains("App Attestation")
    }

    @Test
    fun `fingerprint mismatch fails fast at construction`() {
        assertThrows<IllegalStateException> {
            AppAttestTrustAnchors(
                resourcePath = AppAttestTrustAnchors.RESOURCE_PATH,
                expectedSha256 = "0".repeat(64),
            )
        }.let { exception ->
            assertThat(exception.message).contains("fingerprint mismatch")
        }
    }

    @Test
    fun `missing resource defers clear error to first verification`() {
        val anchors = AppAttestTrustAnchors(resourcePath = "attest/does-not-exist.pem")
        // 构造不失败（降级策略）；第一次取验签器时报明确错误
        val exception = assertThrows<IllegalStateException> {
            anchors.certPathTrustworthinessVerifier()
        }
        assertThat(exception.message).contains("attest/does-not-exist.pem")
        assertThat(exception.message).contains("missing")
    }
}
