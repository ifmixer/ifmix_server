package com.ifmix.core.job.attest

import assertk.assertThat
import assertk.assertions.*
import org.junit.jupiter.api.Test

/**
 * app_attest_config JSONB 最小 DTO 解析（core-job 独立 DTO，Jackson 3 / tools.jackson）。
 */
class AppAttestConfigTest {

    @Test
    fun `完整ios段_字段逐字映射`() {
        val raw = """
            {"ios":{"teamId":"TEAM1","env":"production","deviceCheckKeyId":"abc","deviceCheckPrivateKey":"-----BEGIN...","bundleId":"com.x"}}
        """.trimIndent()
        val cfg = AppAttestConfig.parse(raw)!!
        val ios = cfg.ios!!

        assertThat(ios.teamId).isEqualTo("TEAM1")
        assertThat(ios.env).isEqualTo("production")
        assertThat(ios.deviceCheckKeyId).isEqualTo("abc")
        assertThat(ios.deviceCheckPrivateKey).isEqualTo("-----BEGIN...")
    }

    @Test
    fun `仅teamId_env_env缺失deviceCheck字段_逐字段可空`() {
        val cfg = AppAttestConfig.parse("""{"ios":{"teamId":"T","env":"development"}}""")!!
        val ios = cfg.ios!!

        assertThat(ios.teamId).isEqualTo("T")
        assertThat(ios.env).isEqualTo("development")
        assertThat(ios.deviceCheckKeyId).isNull()
        assertThat(ios.deviceCheckPrivateKey).isNull()
    }

    @Test
    fun `null或空串_返回null`() {
        assertThat(AppAttestConfig.parse(null)).isNull()
        assertThat(AppAttestConfig.parse("")).isNull()
        assertThat(AppAttestConfig.parse("   ")).isNull()
    }

    @Test
    fun `非法JSON_返回null按未配置处理`() {
        assertThat(AppAttestConfig.parse("{not json")).isNull()
        assertThat(AppAttestConfig.parse("[]")).isNull()
    }

    @Test
    fun `ios段缺失_顶层可空`() {
        val cfg = AppAttestConfig.parse("""{"android":{"packageName":"com.x"}}""")!!
        assertThat(cfg.ios).isNull()
    }
}
