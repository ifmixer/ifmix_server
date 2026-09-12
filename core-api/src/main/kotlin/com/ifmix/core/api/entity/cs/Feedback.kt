package com.ifmix.core.api.entity.cs

import com.ifmix.core.api.entity.common.ProjectScopedProps
import com.ifmix.core.api.entity.common.UUIDProps
import com.ifmix.core.api.entity.common.CreatedAtProps
import com.ifmix.core.api.entity.common.CustomerIdProps
import com.ifmix.core.api.entity.common.InstallIdProps
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

/** 反馈来源主题编码。typealias（Int 全链路透传），码表见 [FeedbackTopics]。 */
typealias FeedbackTopic = Int

/** 反馈来源主题码表。0 保留，从 10 起步长 10。 */
object FeedbackTopics {
    const val UNKNOWN: FeedbackTopic = 0
    const val SCAN: FeedbackTopic = 10
    const val DEEP_RESEARCH: FeedbackTopic = 20
    const val APP: FeedbackTopic = 30
}

/**
 * Feedback 实体。追加式写入，不软删（无 @LogicalDeleted）。
 */
@Entity
@Table(name = "cs_feedback")
interface Feedback : UUIDProps, ProjectScopedProps, CreatedAtProps, CustomerIdProps, InstallIdProps, UserPreferenceProps {

    val scanRecordId: UUID?
    /** 反馈来源主题。10=Scan, 20=DeepResearch, 30=App。码表见 [FeedbackTopics]。 */
    val topic: FeedbackTopic
    /** 反馈原因（多选）。码表见 [FeedbackReasons]。存 PG smallint[]。 */
    @Column(sqlElementType = "smallint")
    val reasons: Array<FeedbackReason>
    val comment: String?
    /** 可选联系方式：邮箱（回访用，原样存用户输入，不做结构化校验）。 */
    val email: String?
    /** 可选联系方式：手机号（回访用，原样存用户输入，含区号等由客户端自定）。 */
    val phone: String?
    /** SPM 埋点位置标识 */
    val spm: String?
}
