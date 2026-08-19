package com.ifmix.api.core.entity.feedback

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.CreatedAtProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * Feedback 实体。追加式写入，不软删（无 @LogicalDeleted）。
 */
@Entity
@Table(name = "core_feedback")
interface Feedback : AppScopedProps, CreatedAtProps {

    @Id
    val id: UUID


    val installId: UUID

    @Column(name = "user_id")
    val userId: UUID?

    val scanRecordId: UUID?

    /** 反馈分类编码。0=UNKNOWN, 100=LIKED, 200=PRICE_TOO_HIGH, 210=PRICE_TOO_LOW, 220=PRICE_MISSING, 300=WRONG_IDENTIFICATION, 400=FEATURE_REQUEST, 410=MORE_RECOMMENDATIONS */
    val category: Int

    val comment: String?
}
