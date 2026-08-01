package com.ifmix.api.core.repository.collection

import com.ifmix.api.core.entity.collection.Collection
import com.ifmix.api.core.entity.collection.appId
import com.ifmix.api.core.entity.collection.installId
import com.ifmix.api.core.entity.collection.isDefault
import com.ifmix.api.core.entity.collection.userId
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID

/** Collection repository with custom queries */
@Repository
class CollectionRepository(sql: KSqlClient,) : BaseAppCrudRepository<Collection>(sql, Collection::class) {

    /** Find default collection for app by optional userId or installId */
    fun findDefault(appId: UUID, installId: UUID?, userId: String?): Collection? {
        // Try userId first
        if (userId != null) {
            val byUser = sql.createQuery(Collection::class) {
                where(table.appId eq appId)
                where(table.isDefault eq true)
                where(table.userId eq userId)
                select(table)
            }.fetchOneOrNull()
            if (byUser != null) return byUser
        }
        // Fallback to installId
        if (installId != null) {
            return sql.createQuery(Collection::class) {
                where(table.appId eq appId)
                where(table.isDefault eq true)
                where(table.installId eq installId)
                select(table)
            }.fetchOneOrNull()
        }
        return null
    }
}
