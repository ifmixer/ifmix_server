package com.ifmix.api.core.modules.scan.repo

import com.ifmix.api.core.entity.collection.ScanCollection
import com.ifmix.api.core.entity.collection.appId
import com.ifmix.api.core.entity.collection.installId
import com.ifmix.api.core.entity.collection.isDefault
import com.ifmix.api.core.entity.collection.userId
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ScanCollectionRepository(sql: KSqlClient) : BaseAppCrudRepository<ScanCollection>(sql, ScanCollection::class) {

    fun findDefault(ctx: RepoContext, appId: UUID, installId: UUID?, userId: UUID?): ScanCollection? {
        if (userId != null) {
            val byUser = sql.createQuery(ScanCollection::class) {
                where(table.appId eq appId)
                where(table.isDefault eq true)
                where(table.userId eq userId)
                select(table)
            }.fetchOneOrNull()
            if (byUser != null) return byUser
        }
        if (installId != null) {
            return sql.createQuery(ScanCollection::class) {
                where(table.appId eq appId)
                where(table.isDefault eq true)
                where(table.installId eq installId)
                select(table)
            }.fetchOneOrNull()
        }
        return null
    }
}
