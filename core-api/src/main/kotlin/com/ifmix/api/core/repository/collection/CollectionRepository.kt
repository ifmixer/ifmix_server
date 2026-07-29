package com.ifmix.api.core.repository.collection

import com.ifmix.api.core.entity.collection.Collection
import com.ifmix.api.core.entity.collection.appId
import com.ifmix.api.core.entity.collection.installId
import com.ifmix.api.core.entity.collection.isDefault
import com.ifmix.api.core.entity.collection.userId
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Component
import java.util.UUID

/** Collection repository with custom queries */
@Component
class CollectionRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<Collection>(sql, Collection::class) {

    /** Find default collection for app by optional userId or installId */
    fun findDefault(appId: UUID, installId: String?, userId: String?): Collection? {
        return sql.createQuery(Collection::class) {
            where(table.appId eq appId)
            where(table.isDefault eq true)
            when {
                userId != null -> where(table.userId eq userId)
                installId != null -> where(table.installId eq installId)
                else -> {} // no additional filter
            }
            select(table)
        }.fetchOneOrNull()
    }
}
