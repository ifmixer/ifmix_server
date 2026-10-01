package com.ifmix.core.api.infra.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 终身累计配额上限（与 [RateLimitConfig] 的时间窗口限流不同：本配额只增不减、永不重置）。
 *
 * 持久计数存于 core_ai_customer_scan_metrics.scan_count / deep_research_count；这里只给固定上限。
 * 达到上限后对应操作被 QUOTA_EXCEEDED 拒绝。
 *
 * 示例（application.yml）：
 * ```yaml
 * app:
 *   scanquota:
 *     scan: 5
 *     deep-research: 3
 * ```
 */
@ConfigurationProperties(prefix = "app.scanquota")
data class ScanQuotaConfig(
    /** 终身可执行的普通扫描次数上限。 */
    val scan: Int = 5,
    /** 终身可执行的深度研究次数上限。 */
    val deepResearch: Int = 3,
)
