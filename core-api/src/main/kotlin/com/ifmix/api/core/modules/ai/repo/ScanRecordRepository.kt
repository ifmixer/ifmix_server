package com.ifmix.api.core.modules.ai.repo

import com.ifmix.api.core.entity.ai.ScanRecord
import com.ifmix.api.core.entity.ai.appId
import com.ifmix.api.core.entity.ai.userDisplayName
import com.ifmix.api.core.entity.ai.collected
import com.ifmix.api.core.entity.ai.id
import com.ifmix.api.core.entity.ai.userNotes
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
        return sql.createQuery(ScanRecord::class) {
            where(table.appId eq appId)
            collected?.let { where(table.collected eq it) }
            cursor?.let { where(table.id lt it) }
            orderBy(table.id.desc())
            select(table)
        }.limit(limit).execute()
    }

    fun partialUpdate(ctx: SvcCtx, appId: UUID, id: UUID, req: UpdateScanInput) {
        sql.createUpdate(ScanRecord::class) {
            where(table.appId eq appId)
            where(table.id eq id)
            req.set?.userDisplayName?.let { set(table.userDisplayName, it) }
            req.set?.userNotes?.let { set(table.userNotes, it) }
            req.set?.collected?.let { set(table.collected, it) }
        }.execute()
    }
}
