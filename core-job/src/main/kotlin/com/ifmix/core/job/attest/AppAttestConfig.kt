package com.ifmix.core.job.attest

import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * core-job 侧的 `core_project_server_config.app_attest_config` 最小解析 DTO。
 *
 * 决策（WP-E）：不放 core-common（core-job 无 core-common 之外对该 JSONB 的消费方），
 * 只取回填 / 刷新需要的 ios 段字段，逐字段可空 —— 缺失只影响对应任务并打日志（规格 §4.1 口径：
 * deviceCheck* 是可选字段，缺失不是配置无效）。
 *
 * 字段命名与 core-api `infra/attest/AttestConfig` 的 KEY_* 常量逐字一致（JSONB 原文是 camelCase）。
 * JSONB 列经 PG JDBC 驱动以 string 形式读出（`getString`），Jackson 解析；解析失败返回 null
 * （该行按未配置处理，跳过 + 日志，不报错）。
 */
data class AppAttestIosConfig(
    val teamId: String?,
    val env: String?,
    val deviceCheckKeyId: String?,
    val deviceCheckPrivateKey: String?,
)

data class AppAttestConfig(
    val ios: AppAttestIosConfig?,
) {
    companion object {
        private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

        /** 解析 JSONB 原文；null（列未配置）或解析失败 → null。 */
        fun parse(raw: String?): AppAttestConfig? {
            if (raw.isNullOrBlank()) return null
            return try {
                mapper.readValue(raw, AppAttestConfig::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }
}
