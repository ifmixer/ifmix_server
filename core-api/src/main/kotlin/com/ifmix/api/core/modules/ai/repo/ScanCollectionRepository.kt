package com.ifmix.api.core.modules.ai.repo

import com.ifmix.api.core.entity.ai.ScanCollection
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID
import com.ifmix.api.core.entity.ai.appId
import com.ifmix.api.core.entity.ai.userId
import com.ifmix.api.core.entity.ai.installId
import com.ifmix.api.core.entity.ai.isDefault
import com.ifmix.api.core.entity.ai.id

@Repository
class ScanCollectionRepository(sql: KSqlClient) : BaseAppCrudRepository<ScanCollection>(sql, ScanCollection::class) {

    fun findDefault(ctx: ModuleCtx, appId: UUID, installId: UUID?, userId: UUID?): ScanCollection? {
        if (userId != null) {
            return ctx.sql.createQuery(ScanCollection::class) {
                where(table.get<UUID>("appId") eq appId)
                where(table.isDefault eq true)
                where(table.userId eq userId)
                select(table)
            }.limit(1).execute().firstOrNull()
        }
        if (installId != null) {
            return ctx.sql.createQuery(ScanCollection::class) {
                where(table.get<UUID>("appId") eq appId)
                where(table.isDefault eq true)
                where(table.installId eq installId)
                select(table)
            }.limit(1).execute().firstOrNull()
        }
        return null
    }

    override fun findById(ctx: ModuleCtx, appId: UUID, id: UUID): ScanCollection? {
        return ctx.sql.createQuery(ScanCollection::class) {
            where(table.get<UUID>("appId") eq appId)
            where(table.id eq id)
            select(table)
        }.limit(1).execute().firstOrNull()
    }
}
