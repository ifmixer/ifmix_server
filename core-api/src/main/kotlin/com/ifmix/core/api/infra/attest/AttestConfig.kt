package com.ifmix.core.api.infra.attest

/**
 * App Attest 项目级配置（`core_project_server_config.app_attest_config` JSONB）。
 *
 * 设计规格：`docs/superpowers/specs/2026-10-04-install-attestation-design.md` §4.1。
 *
 * ```json
 * {
 *   "mode": "OBSERVE",
 *   "ios": {
 *     "teamId": "ABCDE12345",
 *     "bundleId": "com.example.antique",
 *     "env": "production",
 *     "deviceCheckKeyId": "KEYID12345",          // 可选，仅 core-job 用
 *     "deviceCheckPrivateKey": "-----BEGIN..."   // 可选，仅 core-job 用
 *   },
 *   "android": {
 *     "packageName": "com.example.antique",
 *     "certSha256Digests": ["base64..."],
 *     "serviceAccount": { ... }                   // 可选（1b）
 *   }
 * }
 * ```
 *
 * 解析口径（§2 决策 4 / §4.1 v5）：
 * - `mode` 缺省 OBSERVE；非法值记入 [AttestConfig.problems]（整体 fail-closed）。
 * - 平台子对象「存在但解析失败」→ 该 provider 不可用（ios/android = null）且记入 problems；
 *   「不存在」不算问题。
 * - `deviceCheckKeyId` / `deviceCheckPrivateKey` / `serviceAccount` 是可选字段：
 *   缺失不影响 createInstall / recoverInstall / createAttestChallenge 的有效性，
 *   只让 core-job 跳过对应任务并打日志。
 */
enum class AttestMode {
    OFF,
    OBSERVE,
    ENFORCE;

    companion object {
        fun parse(raw: Any?): AttestMode? =
            (raw as? String)?.trim()?.uppercase()?.let { v -> entries.firstOrNull { it.name == v } }
    }
}

/** iOS（App Attest）平台配置。 */
data class IosAttestConfig(
    val teamId: String,
    val bundleId: String,
    /** true = production 环境（TestFlight / App Store 构建）；false = development。 */
    val production: Boolean,
    /** DeviceCheck key id（.p8 对应），core-job fraud metric 刷新用；缺失时 core-job 跳过刷新。 */
    val deviceCheckKeyId: String?,
    /** DeviceCheck .p8 私钥（PEM 文本），仅 core-job 用；缺失时 core-job 跳过刷新。 */
    val deviceCheckPrivateKey: String?,
)

/** Android（Play Integrity，1b）平台配置。本期只要求能解析，不做验证实现。 */
data class AndroidAttestConfig(
    val packageName: String,
    val certSha256Digests: List<String>,
    /** service account JSON 是否存在（1b：decodeIntegrityToken 的凭据）。 */
    val serviceAccountPresent: Boolean,
)

/**
 * 解析后的 attest 配置。解析过程不抛异常，问题全部收集进 [problems]。
 */
data class AttestConfig(
    val mode: AttestMode,
    val ios: IosAttestConfig?,
    val android: AndroidAttestConfig?,
    val problems: List<String>,
) {
    /** 配置整体可解析（无任何 problem）。 */
    val isValid: Boolean
        get() = problems.isEmpty()

    /**
     * ENFORCE 完整性检查（§4.1）：至少配置了一个 provider，且所有已配置的子对象都能解析。
     * 可选字段缺失不影响；未配置的 provider 不会被放行（proof → provider_not_configured）。
     */
    fun isValidForEnforce(): Boolean = (ios != null || android != null) && problems.isEmpty()

    companion object {
        /**
         * 从 JSONB 反序列化产物解析。不抛异常。
         * raw == null（列未配置，§4.1「null = 关」）→ mode=OFF、无平台子对象、无 problem。
         */
        fun parse(raw: Map<String, Any?>?): AttestConfig {
            if (raw == null) {
                return AttestConfig(mode = AttestMode.OFF, ios = null, android = null, problems = emptyList())
            }
            val problems = mutableListOf<String>()

            val mode = AttestMode.parse(raw[KEY_MODE])
            if (raw[KEY_MODE] != null && mode == null) {
                problems += "mode: invalid value '${raw[KEY_MODE]}' (expected OFF/OBSERVE/ENFORCE)"
            }

            val ios = parseIos(raw[KEY_IOS], problems)
            val android = parseAndroid(raw[KEY_ANDROID], problems)

            return AttestConfig(
                mode = mode ?: AttestMode.OBSERVE,
                ios = ios,
                android = android,
                problems = problems,
            )
        }

        private fun parseIos(raw: Any?, problems: MutableList<String>): IosAttestConfig? {
            if (raw == null) return null
            val obj = raw as? Map<*, *>
            if (obj == null) {
                problems += "ios: expected an object"
                return null
            }
            val iosProblems = mutableListOf<String>()

            val teamId = obj.optString(KEY_TEAM_ID, iosProblems, required = true)
            val bundleId = obj.optString(KEY_BUNDLE_ID, iosProblems, required = true)
            val envRaw = obj.optString(KEY_ENV, iosProblems, required = true)?.lowercase()
            val production = when (envRaw) {
                ENV_PRODUCTION -> true
                ENV_DEVELOPMENT -> false
                else -> {
                    if (envRaw != null) iosProblems += "env: invalid value '$envRaw' (expected $ENV_PRODUCTION/$ENV_DEVELOPMENT)"
                    null
                }
            }

            // 可选字段：缺失（或空白）→ null，不算解析失败；类型错误算问题（fail-closed）。
            val deviceCheckKeyId = obj.optSecret(KEY_DEVICE_CHECK_KEY_ID, iosProblems)
            val deviceCheckPrivateKey = obj.optSecret(KEY_DEVICE_CHECK_PRIVATE_KEY, iosProblems)

            return if (iosProblems.isEmpty() && teamId != null && bundleId != null && production != null) {
                IosAttestConfig(
                    teamId = teamId,
                    bundleId = bundleId,
                    production = production,
                    deviceCheckKeyId = deviceCheckKeyId,
                    deviceCheckPrivateKey = deviceCheckPrivateKey,
                )
            } else {
                iosProblems.forEach { problems += "ios: $it" }
                null
            }
        }

        private fun parseAndroid(raw: Any?, problems: MutableList<String>): AndroidAttestConfig? {
            if (raw == null) return null
            val obj = raw as? Map<*, *>
            if (obj == null) {
                problems += "android: expected an object"
                return null
            }
            val androidProblems = mutableListOf<String>()

            val packageName = obj.optString(KEY_PACKAGE_NAME, androidProblems, required = true)
            val digests = when (val d = obj[KEY_CERT_SHA256_DIGESTS]) {
                null -> {
                    androidProblems += "certSha256Digests: required"
                    null
                }
                is List<*> -> {
                    val list = d.mapNotNull { it as? String }
                    if (d.isEmpty() || list.size != d.size || list.any { it.isBlank() }) {
                        androidProblems += "certSha256Digests: must be a non-empty list of strings"
                        null
                    } else {
                        list
                    }
                }
                else -> {
                    androidProblems += "certSha256Digests: must be a list of strings"
                    null
                }
            }

            return if (androidProblems.isEmpty() && packageName != null && digests != null) {
                AndroidAttestConfig(
                    packageName = packageName,
                    certSha256Digests = digests,
                    serviceAccountPresent = obj[KEY_SERVICE_ACCOUNT] != null,
                )
            } else {
                androidProblems.forEach { problems += "android: $it" }
                null
            }
        }

        // ---- keys ----
        private const val KEY_MODE = "mode"
        private const val KEY_IOS = "ios"
        private const val KEY_ANDROID = "android"
        private const val KEY_TEAM_ID = "teamId"
        private const val KEY_BUNDLE_ID = "bundleId"
        private const val KEY_ENV = "env"
        private const val KEY_DEVICE_CHECK_KEY_ID = "deviceCheckKeyId"
        private const val KEY_DEVICE_CHECK_PRIVATE_KEY = "deviceCheckPrivateKey"
        private const val KEY_PACKAGE_NAME = "packageName"
        private const val KEY_CERT_SHA256_DIGESTS = "certSha256Digests"
        private const val KEY_SERVICE_ACCOUNT = "serviceAccount"

        private const val ENV_PRODUCTION = "production"
        private const val ENV_DEVELOPMENT = "development"

        private fun Map<*, *>.optString(key: String, problems: MutableList<String>, required: Boolean = false): String? =
            when (val v = this[key]) {
                null -> {
                    if (required) problems += "$key: required"
                    null
                }
                is String -> v.trim().takeIf { it.isNotEmpty() }
                    ?: run {
                        if (required) problems += "$key: required"
                        null
                    }
                else -> {
                    problems += "$key: expected a string"
                    null
                }
            }

        /** 可选 secret 字段：缺失/空白 → null（不算失败）；类型错误 → 问题。 */
        private fun Map<*, *>.optSecret(key: String, problems: MutableList<String>): String? =
            when (val v = this[key]) {
                null -> null
                is String -> v.trim().takeIf { it.isNotEmpty() }
                else -> {
                    problems += "$key: expected a string"
                    null
                }
            }
    }
}
