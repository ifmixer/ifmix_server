package com.ifmix.core.api.infra.attest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * AttestConfig 解析口径测试（规格 §4.1 v5、§2 决策 4）。
 */
class AttestConfigTest {

    private fun iosConfig(
        mode: String? = "OBSERVE",
        teamId: String? = "ABCDE12345",
        bundleId: String? = "com.example.antique",
        env: String? = "production",
        deviceCheckKeyId: String? = "KEYID12345",
        deviceCheckPrivateKey: String? = "-----BEGIN PRIVATE KEY-----",
    ): Map<String, Any?> {
        val ios = mutableMapOf<String, Any?>()
        teamId?.let { ios["teamId"] = it }
        bundleId?.let { ios["bundleId"] = it }
        env?.let { ios["env"] = it }
        deviceCheckKeyId?.let { ios["deviceCheckKeyId"] = it }
        deviceCheckPrivateKey?.let { ios["deviceCheckPrivateKey"] = it }
        val raw = mutableMapOf<String, Any?>()
        mode?.let { raw["mode"] = it }
        raw["ios"] = ios
        return raw
    }

    // ---- mode ----

    @Test
    fun `mode defaults to OBSERVE when missing`() {
        val config = AttestConfig.parse(iosConfig(mode = null))
        assertThat(config.mode).isEqualTo(AttestMode.OBSERVE)
        assertThat(config.isValid).isTrue()
    }

    @Test
    fun `mode parses all three values case-insensitively`() {
        assertThat(AttestConfig.parse(iosConfig(mode = "OFF")).mode).isEqualTo(AttestMode.OFF)
        assertThat(AttestConfig.parse(iosConfig(mode = "observe")).mode).isEqualTo(AttestMode.OBSERVE)
        assertThat(AttestConfig.parse(iosConfig(mode = "ENFORCE")).mode).isEqualTo(AttestMode.ENFORCE)
    }

    @Test
    fun `invalid mode records problem and fails closed`() {
        val config = AttestConfig.parse(iosConfig(mode = "YOLO"))
        assertThat(config.mode).isEqualTo(AttestMode.OBSERVE)
        assertThat(config.isValid).isFalse()
        assertThat(config.problems).anyMatch { it.startsWith("mode:") }
        assertThat(config.isValidForEnforce()).isFalse()
    }

    // ---- 整体形态 ----

    @Test
    fun `null jsonb means off with no problems`() {
        val config = AttestConfig.parse(null)
        assertThat(config.mode).isEqualTo(AttestMode.OFF)
        assertThat(config.ios).isNull()
        assertThat(config.android).isNull()
        assertThat(config.problems).isEmpty()
        assertThat(config.isValid).isTrue()
        assertThat(config.isValidForEnforce()).isFalse()
    }

    @Test
    fun `empty object parses with defaults and no platform`() {
        val config = AttestConfig.parse(emptyMap())
        assertThat(config.mode).isEqualTo(AttestMode.OBSERVE)
        assertThat(config.ios).isNull()
        assertThat(config.android).isNull()
        assertThat(config.problems).isEmpty()
        assertThat(config.isValid).isTrue()
        // 没有任何平台 → ENFORCE 无效
        assertThat(config.isValidForEnforce()).isFalse()
    }

    // ---- ios ----

    @Test
    fun `full ios config is valid for enforce`() {
        val config = AttestConfig.parse(iosConfig(mode = "ENFORCE"))
        assertThat(config.isValid).isTrue()
        assertThat(config.isValidForEnforce()).isTrue()
        val ios = requireNotNull(config.ios)
        assertThat(ios.teamId).isEqualTo("ABCDE12345")
        assertThat(ios.bundleId).isEqualTo("com.example.antique")
        assertThat(ios.production).isTrue()
    }

    @Test
    fun `env development maps to production=false`() {
        val config = AttestConfig.parse(iosConfig(env = "development"))
        assertThat(requireNotNull(config.ios).production).isFalse()
    }

    @Test
    fun `optional deviceCheck fields may be missing - still valid`() {
        // §2 决策 4：deviceCheckKeyId / deviceCheckPrivateKey 缺失不影响 API 路径有效性
        val config = AttestConfig.parse(iosConfig(mode = "ENFORCE", deviceCheckKeyId = null, deviceCheckPrivateKey = null))
        assertThat(config.ios).isNotNull()
        assertThat(config.isValid).isTrue()
        assertThat(config.isValidForEnforce()).isTrue()
        assertThat(requireNotNull(config.ios).deviceCheckKeyId).isNull()
        assertThat(requireNotNull(config.ios).deviceCheckPrivateKey).isNull()
    }

    @Test
    fun `blank optional fields treated as missing - still valid`() {
        val config = AttestConfig.parse(iosConfig(mode = "ENFORCE", deviceCheckKeyId = "  ", deviceCheckPrivateKey = ""))
        assertThat(config.ios).isNotNull()
        assertThat(config.isValid).isTrue()
    }

    @Test
    fun `missing required teamId makes ios unavailable and records problem`() {
        val config = AttestConfig.parse(iosConfig(mode = "ENFORCE", teamId = null))
        assertThat(config.ios).isNull() // provider 不可用
        assertThat(config.isValid).isFalse()
        assertThat(config.problems).anyMatch { it.startsWith("ios: teamId") }
        assertThat(config.isValidForEnforce()).isFalse()
    }

    @Test
    fun `invalid env value makes ios unavailable`() {
        val config = AttestConfig.parse(iosConfig(env = "staging"))
        assertThat(config.ios).isNull()
        assertThat(config.problems).anyMatch { it.startsWith("ios: env") }
        assertThat(config.isValidForEnforce()).isFalse()
    }

    @Test
    fun `wrong-typed optional field records problem`() {
        val config = AttestConfig.parse(mapOf("ios" to mapOf("teamId" to "T", "bundleId" to "b", "env" to "production", "deviceCheckKeyId" to 42)))
        assertThat(config.ios).isNull()
        assertThat(config.problems).anyMatch { it.startsWith("ios: deviceCheckKeyId") }
    }

    @Test
    fun `non-object ios records problem`() {
        val config = AttestConfig.parse(mapOf("ios" to "yes"))
        assertThat(config.ios).isNull()
        assertThat(config.problems).anyMatch { it.startsWith("ios: expected an object") }
    }

    // ---- android ----

    @Test
    fun `android config with service account parses`() {
        val config = AttestConfig.parse(
            mapOf(
                "mode" to "OBSERVE",
                "android" to mapOf(
                    "packageName" to "com.example.antique",
                    "certSha256Digests" to listOf("base64digest1", "base64digest2"),
                    "serviceAccount" to mapOf("type" to "service_account"),
                ),
            ),
        )
        assertThat(config.isValid).isTrue()
        val android = requireNotNull(config.android)
        assertThat(android.packageName).isEqualTo("com.example.antique")
        assertThat(android.certSha256Digests).containsExactly("base64digest1", "base64digest2")
        assertThat(android.serviceAccountPresent).isTrue()
        assertThat(config.isValidForEnforce()).isTrue()
    }

    @Test
    fun `android without serviceAccount is fine - presence flag false`() {
        val config = AttestConfig.parse(
            mapOf(
                "android" to mapOf(
                    "packageName" to "com.example.antique",
                    "certSha256Digests" to listOf("d1"),
                ),
            ),
        )
        assertThat(config.isValid).isTrue()
        assertThat(requireNotNull(config.android).serviceAccountPresent).isFalse()
    }

    @Test
    fun `android empty digests records problem`() {
        val config = AttestConfig.parse(
            mapOf(
                "android" to mapOf(
                    "packageName" to "com.example.antique",
                    "certSha256Digests" to emptyList<String>(),
                ),
            ),
        )
        assertThat(config.android).isNull()
        assertThat(config.problems).anyMatch { it.startsWith("android: certSha256Digests") }
        assertThat(config.isValidForEnforce()).isFalse()
    }

    @Test
    fun `android wrong-typed digests records problem`() {
        val config = AttestConfig.parse(
            mapOf(
                "android" to mapOf("packageName" to "p", "certSha256Digests" to "not-a-list"),
            ),
        )
        assertThat(config.android).isNull()
        assertThat(config.problems).anyMatch { it.startsWith("android: certSha256Digests") }
    }

    // ---- 组合 ----

    @Test
    fun `both platforms valid - enforce ok`() {
        val config = AttestConfig.parse(
            iosConfig(mode = "ENFORCE") + mapOf(
                "android" to mapOf("packageName" to "p", "certSha256Digests" to listOf("d")),
            ),
        )
        assertThat(config.isValidForEnforce()).isTrue()
        assertThat(config.ios).isNotNull()
        assertThat(config.android).isNotNull()
    }

    @Test
    fun `ios broken but android valid - enforce ok and ios provider unavailable`() {
        // ios 存在但坏 → ios provider 不可用；android 可解析 → 至少一个 provider 可用，ENFORCE 有效
        val config = AttestConfig.parse(
            iosConfig(mode = "ENFORCE", teamId = null) + mapOf(
                "android" to mapOf("packageName" to "p", "certSha256Digests" to listOf("d")),
            ),
        )
        assertThat(config.ios).isNull()
        assertThat(config.android).isNotNull()
        assertThat(config.isValid).isFalse() // 有 problem（ios 坏）→ fail-closed 信号由调用方处理
        assertThat(config.isValidForEnforce()).isFalse() // problems 非空
    }
}
