package com.ifmix.api.core.repository.antique

import com.ifmix.api.core.entity.antique.ImageRef
import com.ifmix.api.core.entity.antique.ScanRecord
import com.ifmix.api.core.entity.antique.appId
import com.ifmix.api.core.entity.antique.id
import com.ifmix.api.core.entity.enums.ScanStatus
import com.ifmix.api.core.infra.db.CursorQueryInput
import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.ratelimit.Tier
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import com.ifmix.api.core.infra.db.UuidV7
import java.time.Instant
import java.util.UUID

/** Scan record repository */
@Repository
class ScanRecordRepository(sql: KSqlClient) : BaseAppCrudRepository<ScanRecord>(sql, ScanRecord::class) {

    /** Create a new scan record using Jimmer draft lambda. */
    fun create(
        ctx: OperationContext,
        appId: UUID,
        imageKeys: List<ImageRef>,
        status: ScanStatus,
        tier: Tier,
        clientIp: String?,
        resultJson: String? = null,
    ): ScanRecord {
        val now = Instant.now()
        val entity = ScanRecord {
            id = UuidV7.generate()
            this.appId = appId
            this.imageKeys = imageKeys
            this.resultJson = resultJson
            this.status = status
            this.tier = tier
            this.clientIp = clientIp
            this.createdAt = now
            this.updatedAt = now
        }
        return save(ctx, entity)
    }
}
