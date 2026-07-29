package com.ifmix.api.core.repository.collection

import com.ifmix.api.core.entity.collection.CollectionItem
import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component

/** CollectionItem repository (basic CRUD only) */
@Component
class CollectionItemRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<CollectionItem>(sql, CollectionItem::class)
