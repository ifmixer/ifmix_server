package com.ifmix.api.core.repository.feedback

import com.ifmix.api.core.entity.enums.FeedbackCategory
import com.ifmix.api.core.entity.feedback.Feedback
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class FeedbackRepository(sql: KSqlClient) : BaseAppCrudRepository<Feedback>(sql, Feedback::class) {

    /**
     * 追加式写入反馈。
     * appId / installId / userId / id / createdAt 由服务端注入，不接受客户端传入。
     */
    fun create(
        ctx: OperationContext,
        appId: UUID,
        installId: UUID,
        userId: UUID?,
        category: FeedbackCategory,
        comment: String?,
        scanRecordId: UUID?,
    ): Feedback {
        val entity = Feedback {
            id = UuidV7.generate()
            this.appId = appId
            this.installId = installId
            this.userId = userId
            this.scanRecordId = scanRecordId
            this.category = category
            this.comment = comment
            createdAt = Instant.now()
        }
        return sql.entities.save(entity) { setMode(SaveMode.INSERT_ONLY) }.modifiedEntity
    }
}
