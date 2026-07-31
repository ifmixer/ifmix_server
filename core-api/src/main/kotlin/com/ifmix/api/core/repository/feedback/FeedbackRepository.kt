package com.ifmix.api.core.repository.feedback

import com.ifmix.api.core.entity.feedback.Feedback
import com.ifmix.api.core.entity.feedback.dto.FeedbackCreateInput
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class FeedbackRepository(sql: KSqlClient) : BaseAppCrudRepository<Feedback>(sql, Feedback::class) {

    /**
     * 追加式写入反馈。
     * appId / id / createdAt 由服务端注入，不接受客户端传入。
     */
    fun create(appId: java.util.UUID, input: FeedbackCreateInput): Feedback {
        val entity = Feedback {
            id = UuidV7.generate()
            this.appId = appId
            installId = input.installId
            userId = input.userId
            scanRecordId = input.scanRecordId
            category = input.category
            comment = input.comment
            createdAt = Instant.now()
        }
        return sql.entities.save(entity) { setMode(SaveMode.INSERT_ONLY) }.modifiedEntity
    }
}
