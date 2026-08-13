package com.ifmix.api.core.modules.scan.repo

import com.ifmix.api.core.entity.scan.ImageRef
import com.ifmix.api.core.entity.scan.ScanRecord
import com.ifmix.api.core.entity.scan.collected
import com.ifmix.api.core.entity.scan.updatedAt
import com.ifmix.api.core.entity.scan.userDisplayName
import com.ifmix.api.core.entity.scan.userNotes
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.dto.Page
import com.ifmix.api.core.modules.scan.dto.ScanQueryInput
import com.ifmix.api.core.modules.scan.dto.UpdateScanReq
import com.ifmix.api.core.infra.jimmer.ClusterRegistry
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class ScanRecordRepository(
    clusterRegistry: ClusterRegistry,
) : BaseAppCrudRepository<ScanRecord>(clusterRegistry, ScanRecord::class) {


    fun update(ctx: RepoContext, req: UpdateScanReq) {
        writerSql(ctx).createUpdate(ScanRecord::class) {
            where(table.getId<UUID>() eq req.id)
            req.name?.let { set(table.userDisplayName, it) }
            req.userNotes?.let { set(table.userNotes, it) }
            req.collected?.let { set(table.collected, it) }
            set(table.updatedAt, Instant.now())
        }.execute()
    }

    fun findByCursor(ctx: RepoContext, input: ScanQueryInput): Page<ScanRecord> {
        val limit = input.limit.coerceIn(1, 100)
        val cursor = input.cursor?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }

        val items = sql(ctx).createQuery(ScanRecord::class) {
            if (cursor != null) {
                where(table.getId<UUID>() lt cursor)
            }
            if (input.collected != null) {
                where(table.collected eq input.collected)
            }
            orderBy(table.getId<UUID>().desc())
            select(table)
        }.limit(limit + 1).execute()

        return Page.of(items, limit) { getEntityId(it)?.toString() }
    }
}
