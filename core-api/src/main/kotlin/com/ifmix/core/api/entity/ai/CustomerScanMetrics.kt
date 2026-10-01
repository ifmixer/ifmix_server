package com.ifmix.core.api.entity.ai

import com.ifmix.core.api.entity.common.BaseProjectEntity
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * Customer 维度的扫描累计计数（终身配额用）。每 customer 至多一行，行不存在 = 计数均为 0。
 * customerId 为逻辑外键（跨模块，不用 @ManyToOne）。
 */
@Entity
@Table(name = "core_ai_customer_scan_metrics")
@KeyUniqueConstraint
interface CustomerScanMetrics : BaseProjectEntity {

    @Key
    @Column(name = "customer_id")
    val customerId: UUID

    /** 累计成功扫描次数（saveNewScan 成功时 +1）。 */
    val scanCount: Int

    /** 累计成功深度研究次数（saveDeepResearch 成功时 +1）。 */
    val deepResearchCount: Int
}
