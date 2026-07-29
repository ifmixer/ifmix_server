package com.ifmix.api.core.common.jimmer.repository.antique

import com.ifmix.api.core.common.jimmer.base.BaseAppCrudRepository
import com.ifmix.api.core.common.jimmer.entity.antique.ScanRecord
import org.babyfish.jimmer.Input
import org.babyfish.jimmer.sql.kt.*
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** Scan record repository */
@Component
class ScanRecordRepository(
    private val sql: KSqlClient,
) : BaseAppCrudRepository<ScanRecord>(sql, ScanRecord::class) {

    /** Simple find by scanId - uses findAll() + filter for now. */
    fun findByScanId(scanId: String): ScanRecord? =
        findAll().firstOrNull { it.scanId == scanId && it.deletedAt == null }

    /** Create a new scan record using Input API. */
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
        val input: Input<ScanRecord> = sql.input(ScanRecord::class.java) {
            set("id", UUID.randomUUID())
            set("appId", UUID.fromString(appId))
            set("scanId", scanId)
            set("imageUrl", imageUrl)
            set("status", status)
            set("tier", tier)
            set("relatedId", relatedId)
            set("clientIp", clientIp)
            set("createdAt", now)
            set("updatedAt", now)
        }
        return insert(input)
    }
}
