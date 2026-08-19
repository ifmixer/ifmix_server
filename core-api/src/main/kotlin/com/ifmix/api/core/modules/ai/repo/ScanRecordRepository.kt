package com.ifmix.api.core.modules.ai.repo

import com.ifmix.api.core.entity.ai.ScanRecord
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import com.ifmix.api.core.generated.types.UpdateScanInput
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.lt
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ScanRecordRepository(sql: KSqlClient) : BaseAppCrudRepository<ScanRecord>(sql, ScanRecord::class) {

    fun findByCursor(ctx: SvcCtx, appId: UUID, collected: Boolean?, cursor: UUID?, limit: Int): List<ScanRecord> {
        return ctx.sql.createQuery(ScanRecord::class) {
            where(table.get<UUID>("appId") eq appId)
            collected?.let { where(table.collected eq it) }
            cursor?.let { where(table.id lt it) }
            orderBy(table.id.desc())
            select(table)
        }.limit(limit).execute()
    }

    fun partialUpdate(ctx: SvcCtx, appId: UUID, id: UUID, req: UpdateScanInput) {
        ctx.sql.createUpdate(ScanRecord::class) {
            where(table.get<UUID>("appId") eq appId)
            where(table.id eq id)
            req.set?.userDisplayName?.let { set(table.userDisplayName, it) }
            req.set?.userNotes?.let { set(table.userNotes, it) }
            req.set?.collected?.let { set(table.collected, it) }
        }.execute()
    }
}
