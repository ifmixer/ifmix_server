package com.ifmix.api.core.entity.cms

import com.ifmix.api.core.entity.common.AppScopedProps
import com.ifmix.api.core.entity.common.UUIDProps
import com.ifmix.api.core.entity.common.CreatedAtProps
import com.ifmix.api.core.entity.common.CustomerOwnedProps
import com.ifmix.api.core.entity.common.UserPreferenceProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * Feedback 实体。追加式写入，不软删（无 @LogicalDeleted）。
 */
@Entity
@Table(name = "cms_feedback")
interface Feedback : UUIDProps, AppScopedProps, CreatedAtProps, CustomerOwnedProps, UserPreferenceProps {

    val scanRecordId: UUID?
    /** 反馈分类编码。0=UNKNOWN, 10=LIKED, 20=PRICE_TOO_HIGH, 21=PRICE_TOO_LOW, 22=PRICE_MISSING, 23=PRICE_UNREASONABLE, 30=WRONG_IDENTIFICATION, 40=FEATURE_REQUEST, 41=MORE_RECOMMENDATIONS */
    val category: Int
    val comment: String?
    /** SPM 埋点位置标识 */
    val spm: String?
}
