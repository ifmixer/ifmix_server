package com.ifmix.api.core.repository.collection

import com.ifmix.api.core.entity.collection.ScanCollection
import com.ifmix.api.core.entity.collection.appId
import com.ifmix.api.core.entity.collection.installId
import com.ifmix.api.core.entity.collection.isDefault
import com.ifmix.api.core.entity.collection.userId
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ScanCollectionRepository(sql: KSqlClient) : BaseAppCrudRepository<ScanCollection>(sql, ScanCollection::class) {

    /** Find default collection for app by optional userId or installId */
    fun findDefault(ctx: OperationContext, appId: UUID, installId: UUID?, userId: UUID?): ScanCollection? {
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
