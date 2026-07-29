package com.ifmix.api.core.common.jimmer.repository.antique

import com.ifmix.api.core.common.jimmer.base.BaseAppCrudRepository
import com.ifmix.api.core.common.jimmer.entity.antique.ScanRecord
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** Scan record repository */
@Component
class ScanRecordRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<ScanRecord>(sql, ScanRecord::class) {

    /** Simple find by scanId. */
    fun findByScanId(scanId: String): ScanRecord? =
        findAll().firstOrNull { it.scanId == scanId }

    /** Create a new scan record using Jimmer draft lambda. */
    fun create(
        appId: String,
        scanId: String?,
        imageUrl: String?,
        status: String?,
        tier: String?,
        relatedId: String?,
        clientIp: String?,
    ): ScanRecord {
        val now = Instant.now()
        val entity = ScanRecord {
            id = UUID.randomUUID()
            this.appId = UUID.fromString(appId)
            this.scanId = scanId
            this.imageUrl = imageUrl
            this.status = status
            this.tier = tier
            this.relatedId = relatedId
            this.clientIp = clientIp
            this.createdAt = now
            this.updatedAt = now
        }
        return save(entity)
    }
}
