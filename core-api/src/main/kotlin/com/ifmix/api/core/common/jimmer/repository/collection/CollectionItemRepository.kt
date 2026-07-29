package com.ifmix.api.core.common.jimmer.repository.collection

import com.ifmix.api.core.common.jimmer.entity.collection.CollectionItem
import com.ifmix.api.core.common.jimmer.base.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component

/** CollectionItem repository (basic CRUD only) */
@Component
class CollectionItemRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<CollectionItem>(sql, CollectionItem::class)
