package com.ifmix.core.api.infra.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 按 action 显式配置的限流阈值（install attestation 规格 §4.6 + 实现计划 §2 决策 6）。
 *
 * - IP 层 = 系统防护（阈值大）；install 层 = 防滥用（阈值小，接线任务 WP-D 使用）。
 *   （v1.0.6 起 legacy 层——无 installId 旧客户端的独立严格阈值——随 legacy fallback 一并删除。）
 * - **重启生效**：不做热更新，不能当即时 kill switch；紧急降额走一次重启/发布。
 * - 与 [RateLimitConfig]（tier 日限额，free/pro/enterprise 平铺字段）共用 `app.ratelimit` 前缀，
 *   字段互不重叠，Spring 各自绑定互不影响。
 */
@ConfigurationProperties(prefix = "app.ratelimit")
data class RateLimitProperties(
    /** m_auth_install_create：入口短窗口 + 日窗口（按验证结果分桶 attested/unverified）。 */
    val install: Install = Install(),
    /** m_auth_install_createAttestChallenge：纯 HMAC 计算，与 createInstall 入口对齐（避免 CGNAT 瓶颈）。 */
    val attestChallenge: AttestChallenge = AttestChallenge(),
    /** m_auth_install_recover。 */
    val recoverInstall: RecoverInstall = RecoverInstall(),
    /** m_auth_install_attest：存量补证。 */
    val attestExisting: AttestExisting = AttestExisting(),
    /** m_auth_customer_createAnonymous。 */
    val anonymous: Anonymous = Anonymous(),
    /** m_ai_scan_createOne。 */
    val scan: Scan = Scan(),
    /** m_ai_deepResearch_run。 */
    val deepResearch: DeepResearch = DeepResearch(),
) {
    /** install: ip-minute=入口短窗口；unverified/attested-ip-day=验签后日窗口两个独立计数器。 */
    data class Install(
        val ipMinute: Int = 100,
        val unverifiedIpDay: Int = 100,
        val attestedIpDay: Int = 1000,
    )

    data class AttestChallenge(
        val ipMinute: Int = 100,
    )

    data class RecoverInstall(
        val ipMinute: Int = 10,
    )

    data class AttestExisting(
        val ipMinute: Int = 10,
        /** 每 install 每 UTC 日新 key 额度。 */
        val installDay: Int = 3,
    )

    data class Anonymous(
        val ipMinute: Int = 100,
        val ipDay: Int = 1000,
        val installDay: Int = 5,
    )

    data class Scan(
        val ipMinute: Int = 100,
        val ipDay: Int = 1000,
        val installMinute: Int = 5,
        val installDay: Int = 100,
    )

    data class DeepResearch(
        val ipMinute: Int = 100,
        val ipDay: Int = 1000,
        val installMinute: Int = 5,
        val installDay: Int = 100,
    )
}
