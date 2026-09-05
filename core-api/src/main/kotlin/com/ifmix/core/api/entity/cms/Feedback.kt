package com.ifmix.core.api.entity.cms

import com.ifmix.core.api.entity.common.AppScopedProps
import com.ifmix.core.api.entity.common.UUIDProps
import com.ifmix.core.api.entity.common.CreatedAtProps
import com.ifmix.core.api.entity.common.CustomerIdProps
import com.ifmix.core.api.entity.common.UserPreferenceProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/** 反馈分类编码。typealias（Int 全链路透传），码表见 [FeedbackCategories]。 */
typealias FeedbackCategory = Int

/** 反馈分类码表。0 保留，从 10 起步长 10（价格类子项用个位细分）。 */
object FeedbackCategories {
    const val UNKNOWN: FeedbackCategory = 0
    const val LIKED: FeedbackCategory = 10
    const val PRICE_TOO_HIGH: FeedbackCategory = 20
    const val PRICE_TOO_LOW: FeedbackCategory = 21
    const val PRICE_MISSING: FeedbackCategory = 22
    const val PRICE_UNREASONABLE: FeedbackCategory = 23
    const val WRONG_IDENTIFICATION: FeedbackCategory = 30
    const val FEATURE_REQUEST: FeedbackCategory = 40
    const val MORE_RECOMMENDATIONS: FeedbackCategory = 41
}

/**
 * Feedback 实体。追加式写入，不软删（无 @LogicalDeleted）。
 */
@Entity
@Table(name = "cms_feedback")
interface Feedback : UUIDProps, AppScopedProps, CreatedAtProps, CustomerIdProps, UserPreferenceProps {

    val scanRecordId: UUID?
    /** 反馈分类编码。0=UNKNOWN, 10=LIKED, 20=PRICE_TOO_HIGH, 21=PRICE_TOO_LOW, 22=PRICE_MISSING, 23=PRICE_UNREASONABLE, 30=WRONG_IDENTIFICATION, 40=FEATURE_REQUEST, 41=MORE_RECOMMENDATIONS */
    val category: FeedbackCategory
    val comment: String?
    /** SPM 埋点位置标识 */
    val spm: String?
}
