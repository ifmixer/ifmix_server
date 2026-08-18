package com.ifmix.api.core.infra.ratelimit

import com.ifmix.api.core.model.enums.Tier
import org.springframework.boot.context.properties.ConfigurationProperties

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
@ConfigurationProperties(prefix = "app.ratelimit")
data class RateLimitConfig(
    val free: Int = 100,
    val pro: Int = 10_000,
    val enterprise: Int = 1_000_000,
) {
    fun limitFor(tier: Tier): Int = when (tier) {
        Tier.UNKNOWN, Tier.FREE -> free
        Tier.PRO -> pro
        Tier.ENTERPRISE -> enterprise
    }
}
