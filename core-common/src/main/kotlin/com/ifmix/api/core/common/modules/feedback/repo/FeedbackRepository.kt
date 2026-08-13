package com.ifmix.api.core.common.modules.feedback.repo

import com.ifmix.api.core.common.entity.feedback.Feedback
import com.ifmix.api.core.common.infra.db.RepoContext
import com.ifmix.api.core.common.infra.db.UuidV7
import com.ifmix.api.core.common.infra.jimmer.ClusterRegistry
import com.ifmix.api.core.common.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class FeedbackRepository(
    clusterRegistry: ClusterRegistry,
) : BaseAppCrudRepository<Feedback>(clusterRegistry, Feedback::class) {

    fun create(
        ctx: RepoContext,
        appId: UUID,
        installId: UUID,
        userId: UUID?,
        category: Int,
        comment: String?,
        scanRecordId: UUID?,
    ): UUID {
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
        return writerSql(ctx).entities.save(entity) { setMode(SaveMode.INSERT_ONLY) }.modifiedEntity.id
    }
}
