package com.ifmix.api.core.repository.collection

import com.ifmix.api.core.entity.collection.Collection
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component

/** Collection repository (basic CRUD only) */
@Component
class CollectionRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<Collection>(sql, Collection::class)
