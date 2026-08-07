package com.ifmix.api.core.modules.scan.repo

import com.ifmix.api.core.entity.scan.ImageRef
import com.ifmix.api.core.entity.scan.ScanRecord
import com.ifmix.api.core.entity.enums.ScanStatus
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.modules.scan.dto.ScanResult
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class ScanRecordRepository(sql: KSqlClient) : BaseAppCrudRepository<ScanRecord>(sql, ScanRecord::class) {

    fun create(
        ctx: RepoContext,
        appId: UUID,
        imageKeys: List<ImageRef>,
        status: ScanStatus,
        clientIp: String?,
        result: ScanResult? = null,
    ): ScanRecord {
        val now = Instant.now()
        val entity = ScanRecord {
            id = UuidV7.generate()
            this.appId = appId
            this.imageKeys = imageKeys
            this.result = result
            this.status = status
            this.clientIp = clientIp
            this.createdAt = now
            this.updatedAt = now
        }
        return save(ctx, entity)
    }
}
