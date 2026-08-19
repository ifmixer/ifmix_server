package com.ifmix.api.core.modules.ai.repo

import com.ifmix.api.core.entity.ai.ScanCollection
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ScanCollectionRepository(sql: KSqlClient) : BaseAppCrudRepository<ScanCollection>(sql, ScanCollection::class) {

    fun findDefault(ctx: SvcCtx, appId: UUID, installId: UUID?, userId: UUID?): ScanCollection? {
        if (userId != null) {
            return sql.createQuery(ScanCollection::class) {
                where(table.appId eq appId)
                where(table.isDefault eq true)
                where(table.userId eq userId)
                select(table)
            }.limit(1).execute().firstOrNull()
        }
        if (installId != null) {
            return sql.createQuery(ScanCollection::class) {
                where(table.appId eq appId)
                where(table.isDefault eq true)
                where(table.installId eq installId)
                select(table)
            }.limit(1).execute().firstOrNull()
        }
        return null
    }

    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): ScanCollection? {
        return sql.createQuery(ScanCollection::class) {
            where(table.appId eq appId)
            where(table.id eq id)
            select(table)
        }.limit(1).execute().firstOrNull()
    }
}
