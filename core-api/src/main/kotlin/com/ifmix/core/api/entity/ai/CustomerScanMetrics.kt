package com.ifmix.core.api.entity.ai

import com.ifmix.core.api.entity.common.BaseProjectEntity
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * Customer 维度的扫描累计计数（终身配额用）。每 customer 至多一行，行不存在 = 计数均为 0。
 * customerId 为逻辑外键（跨模块，不用 @ManyToOne）。
 */
@Entity
@Table(name = "core_ai_scanmetrics")
@KeyUniqueConstraint
interface CustomerScanMetrics : BaseProjectEntity {

    @Key
    @Column(name = "customer_id")
    val customerId: UUID

    /** 已完成并扣除额度的扫描次数。 */
    val scanCount: Int

    /** 已创建但尚未终结的扫描 reservation，占用 scan quota。 */
    @Column(name = "pending_scan_count")
    val pendingScanCount: Int

    /** 累计成功深度研究次数（saveDeepResearch 成功时 +1）。 */
    val deepResearchCount: Int
}
