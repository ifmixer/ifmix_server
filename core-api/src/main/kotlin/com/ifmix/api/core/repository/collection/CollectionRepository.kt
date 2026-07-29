package com.ifmix.api.core.repository.collection

import com.ifmix.api.core.entity.collection.Collection
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import java.util.UUID

/** Collection repository with custom queries */
@Component
class CollectionRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<Collection>(sql, Collection::class) {

    /** Find default collection for app by optional userId or installId */
    fun findDefault(appId: UUID, installId: String?, userId: String?): Collection? {
        val all = findAll()
        return all.firstOrNull { c ->
            c.appId == appId &&
            c.isDefault == true &&
            when {
                userId != null -> c.userId == userId
                installId != null -> c.installId == installId
                else -> true
            }
        }
    }
}
