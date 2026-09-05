package com.ifmix.core.api.entity.cms

import com.ifmix.core.api.entity.common.AppScopedProps
import com.ifmix.core.api.entity.common.UUIDProps
import com.ifmix.core.api.entity.common.CreatedAtProps
import com.ifmix.core.api.entity.common.CustomerIdProps
import com.ifmix.core.api.entity.common.UserPreferenceProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/** 反馈原因编码。typealias（Int 全链路透传），码表见 [FeedbackReasons]。 */
typealias FeedbackReason = Int

/** 反馈原因码表。0 保留，从 10 起步长 10（价格类子项用个位细分）。 */
object FeedbackReasons {
    const val UNKNOWN: FeedbackReason = 0
    const val LIKED: FeedbackReason = 10
    const val PRICE_TOO_HIGH: FeedbackReason = 20
    const val PRICE_TOO_LOW: FeedbackReason = 21
    const val PRICE_MISSING: FeedbackReason = 22
    const val PRICE_UNREASONABLE: FeedbackReason = 23
    const val WRONG_IDENTIFICATION: FeedbackReason = 30
    const val FEATURE_REQUEST: FeedbackReason = 40
    const val MORE_RECOMMENDATIONS: FeedbackReason = 41
}

/**
 * Feedback 实体。追加式写入，不软删（无 @LogicalDeleted）。
 */
@Entity
@Table(name = "cms_feedback")
interface Feedback : UUIDProps, AppScopedProps, CreatedAtProps, CustomerIdProps, UserPreferenceProps {

    val scanRecordId: UUID?
    /** 反馈原因（多选）。码表见 [FeedbackReasons]。存 PG smallint[]。 */
    @Column(sqlElementType = "smallint")
    val reasons: Array<FeedbackReason>
    val comment: String?
    /** SPM 埋点位置标识 */
    val spm: String?
}
