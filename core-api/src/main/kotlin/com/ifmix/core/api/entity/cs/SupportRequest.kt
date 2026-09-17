package com.ifmix.core.api.entity.cs

import com.ifmix.core.api.entity.common.BaseProjectEntity
import com.ifmix.core.api.entity.common.CustomerIdProps
import com.ifmix.core.api.entity.common.InstallIdProps
import com.ifmix.core.api.entity.common.MediaRef
import com.ifmix.core.api.entity.common.UserPreferenceProps
import org.babyfish.jimmer.sql.Column
import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.Serialized
import org.babyfish.jimmer.sql.Table
import java.time.Instant

/** 工单状态编码。typealias（Int 全链路透传），码表见 [SupportRequestStatuses]。 */
typealias SupportRequestStatus = Int

/** 工单状态码表（0 保留，从 10 起步长 10）。 */
object SupportRequestStatuses {
    const val UNKNOWN: SupportRequestStatus = 0
    const val OPEN: SupportRequestStatus = 10
    const val IN_PROGRESS: SupportRequestStatus = 20
    const val PENDING_CUSTOMER: SupportRequestStatus = 30
    const val RESOLVED: SupportRequestStatus = 40
    const val CLOSED: SupportRequestStatus = 50
}

/** 工单分类编码。typealias（Int 全链路透传），码表见 [SupportRequestCategories]。客户端可传未登记的值。 */
typealias SupportRequestCategory = Int

/** 工单分类码表（0=默认/未指定，从 10 起步长 10）。允许客户端传未登记值。 */
object SupportRequestCategories {
    const val UNSPECIFIED: SupportRequestCategory = 0
    const val BUG: SupportRequestCategory = 10
    const val FEATURE_REQUEST: SupportRequestCategory = 20
    const val ACCOUNT: SupportRequestCategory = 30
    const val PAYMENT: SupportRequestCategory = 40
    const val CONTENT_ERROR: SupportRequestCategory = 50
    const val OTHER: SupportRequestCategory = 1000
}

/**
 * 用户支持工单（意见反馈 / 联系我们）。
 *
 * customer 侧目前仅创建 + 查询自己的工单；agent 侧的状态流转/回复接口尚未实现，
 * 但 status 与各回复时间戳字段已预留，便于后续扩展。追加式，不软删。
 */
@Entity
@Table(name = "cs_support_request")
interface SupportRequest : BaseProjectEntity, InstallIdProps, CustomerIdProps, UserPreferenceProps {

    val title: String
    val message: String
    val email: String?
    val phone: String?

    /** 工单分类。码表见 [SupportRequestCategories]（允许未登记值）。 */
    val category: SupportRequestCategory

    /** 工单状态。码表见 [SupportRequestStatuses]。创建时默认 10=OPEN。 */
    val status: SupportRequestStatus

    /** 附件（MediaRef 列表）。JSONB。 */
    @Serialized
    val attachments: List<MediaRef>?

    /** 首次 agent 回复时间。 */
    @Column(name = "first_replied_at")
    val firstRepliedAt: Instant?

    /** 最近一次 agent 回复时间。 */
    @Column(name = "last_agent_replied_at")
    val lastAgentRepliedAt: Instant?

    /** 最近一次 customer 回复时间。 */
    @Column(name = "last_customer_replied_at")
    val lastCustomerRepliedAt: Instant?

    @Column(name = "resolved_at")
    val resolvedAt: Instant?

    @Column(name = "closed_at")
    val closedAt: Instant?
}
