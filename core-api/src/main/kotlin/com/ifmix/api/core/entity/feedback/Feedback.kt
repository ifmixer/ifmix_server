package com.ifmix.api.core.entity.feedback

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.CreatedAtProps
import com.ifmix.api.core.entity.enums.FeedbackCategory
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

    override val appId: UUID

    val installId: UUID

    @Column(name = "user_id")
    val userId: UUID?

    val scanRecordId: UUID?

    val category: FeedbackCategory

    val comment: String?
}
