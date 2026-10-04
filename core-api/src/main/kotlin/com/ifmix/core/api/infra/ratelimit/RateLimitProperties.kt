package com.ifmix.core.api.infra.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 按 action 显式配置的限流阈值（install attestation 规格 §4.6 + 实现计划 §2 决策 6）。
 *
 * - IP 层 = 系统防护（阈值大）；legacy = 无 tokenInstallId 的旧客户端（沿用严格阈值，独立计数器）；
 *   install 层 = 防滥用（阈值小，接线任务 WP-D 使用）。
 * - **重启生效**：不做热更新，不能当即时 kill switch；紧急降额走一次重启/发布。
 * - 与 [RateLimitConfig]（tier 日限额，free/pro/enterprise 平铺字段）共用 `app.ratelimit` 前缀，
 *   字段互不重叠，Spring 各自绑定互不影响。
 */
@ConfigurationProperties(prefix = "app.ratelimit")
data class RateLimitProperties(
    /** m_install_createInstall：入口短窗口 + 日窗口（按验证结果分桶 attested/unverified）。 */
    val install: Install = Install(),
    /** m_install_createAttestChallenge：纯 HMAC 计算，与 createInstall 入口对齐（避免 CGNAT 瓶颈）。 */
    val attestChallenge: AttestChallenge = AttestChallenge(),
    /** m_install_recoverInstall。 */
    val recoverInstall: RecoverInstall = RecoverInstall(),
    /** m_install_attestExisting：存量补证。 */
    val attestExisting: AttestExisting = AttestExisting(),
    /** m_customer_createAnonymousCustomer。 */
    val anonymous: Anonymous = Anonymous(),
    /** m_ai_createScan。 */
    val scan: Scan = Scan(),
    /** m_ai_runDeepResearch。 */
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
        val legacyIpMinute: Int = 10,
        val ipMinute: Int = 100,
        val ipDay: Int = 1000,
        val installDay: Int = 5,
    )

    data class Scan(
        val legacyIpMinute: Int = 5,
        val legacyIpDay: Int = 500,
        val ipMinute: Int = 100,
        val ipDay: Int = 1000,
        val installMinute: Int = 5,
        val installDay: Int = 100,
    )

    data class DeepResearch(
        val legacyIpMinute: Int = 3,
        val legacyIpDay: Int = 300,
        val ipMinute: Int = 100,
        val ipDay: Int = 1000,
        val installMinute: Int = 5,
        val installDay: Int = 100,
    )
}
