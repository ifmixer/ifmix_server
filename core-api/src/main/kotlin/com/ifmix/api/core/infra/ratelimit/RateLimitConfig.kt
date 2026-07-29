package com.ifmix.api.core.infra.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 各 Tier 的日限额配置。键为 Tier 名称（FREE / PRO / ENTERPRISE），
 * 值为每日允许的最大请求数。
 *
 * 示例（application.yml）：
 * ```yaml
 * app:
 *   ratelimit:
 *     free: 100
 *     pro: 10000
 *     enterprise: 1000000
 * ```
 */
@Component
@ConfigurationProperties(prefix = "app.ratelimit")
data class RateLimitConfig(
    val free: Int = 100,
    val pro: Int = 10_000,
    val enterprise: Int = 1_000_000,
) {
    fun limitFor(tier: Tier): Int = when (tier) {
        Tier.FREE -> free
        Tier.PRO -> pro
        Tier.ENTERPRISE -> enterprise
    }
}
