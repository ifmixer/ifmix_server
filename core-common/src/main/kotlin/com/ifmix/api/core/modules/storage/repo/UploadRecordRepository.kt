package com.ifmix.api.core.modules.storage.repo

import com.ifmix.api.core.entity.storage.UploadRecord
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.jimmer.ClusterRegistry
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class UploadRecordRepository(
    clusterRegistry: ClusterRegistry,
) : BaseAppCrudRepository<UploadRecord>(clusterRegistry, UploadRecord::class) {

    fun create(
        ctx: RepoContext,
        id: UUID,
        appId: UUID,
        installId: UUID?,
        userId: UUID?,
        objectKey: String,
        contentType: String,
        category: String,
        clientIp: String?,
    ): UploadRecord {
        val entity = UploadRecord {
            this.id = id
            this.appId = appId
            this.installId = installId
            this.userId = userId
            this.objectKey = objectKey
            this.contentType = contentType
            this.category = category
            this.clientIp = clientIp
            createdAt = Instant.now()
        }
        return writerSql(ctx).entities.save(entity) { setMode(SaveMode.INSERT_ONLY) }.modifiedEntity
    }
}
