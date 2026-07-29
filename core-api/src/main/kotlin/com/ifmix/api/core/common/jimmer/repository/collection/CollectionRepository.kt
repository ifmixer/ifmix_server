package com.ifmix.api.core.common.jimmer.repository.collection

import com.ifmix.api.core.common.jimmer.entity.collection.Collection
import com.ifmix.api.core.common.jimmer.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component

/** Collection repository (basic CRUD only) */
@Component
class CollectionRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<Collection>(sql, Collection::class)
