package com.ifmix.api.core.modules.scan.repo

import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.jooq.CrudRepoOps
import com.ifmix.api.core.jooq.tables.CoreScanCollection.Companion.CORE_SCAN_COLLECTION
import com.ifmix.api.core.model.ScanCollection
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ScanCollectionRepository(private val crud: CrudRepoOps) {

    fun findDefault(ctx: RepoContext, appId: UUID, installId: UUID?, userId: UUID?): ScanCollection? {
        // If userId is provided, prefer user-scoped default collection.
        if (userId != null) {
            // Note: userId in DB is VARCHAR (not UUID), so we compare as string.
            val record = ctx.dsl.selectFrom(CORE_SCAN_COLLECTION)
                .where(CORE_SCAN_COLLECTION.APP_ID.eq(appId))
                .and(CORE_SCAN_COLLECTION.IS_DEFAULT.eq(true))
                .and(CORE_SCAN_COLLECTION.USER_ID.eq(userId.toString()))
                .and(CORE_SCAN_COLLECTION.DELETED_AT.isNull)
                .fetchOne()
            if (record != null) return toModel(record as org.jooq.Record)
        }
        // Fall back to install-scoped default collection.
        if (installId != null) {
            val record = ctx.dsl.selectFrom(CORE_SCAN_COLLECTION)
                .where(CORE_SCAN_COLLECTION.APP_ID.eq(appId))
                .and(CORE_SCAN_COLLECTION.IS_DEFAULT.eq(true))
                .and(CORE_SCAN_COLLECTION.INSTALL_ID.eq(installId))
                .and(CORE_SCAN_COLLECTION.DELETED_AT.isNull)
                .fetchOne()
            if (record != null) return toModel(record as org.jooq.Record)
        }
        return null
    }

    fun insert(ctx: RepoContext, collection: ScanCollection) {
        crud.insert(ctx, CORE_SCAN_COLLECTION, collection)
    }

    fun findById(ctx: RepoContext, appId: UUID, id: UUID): ScanCollection? {
        val record = ctx.dsl.selectFrom(CORE_SCAN_COLLECTION)
            .where(CORE_SCAN_COLLECTION.APP_ID.eq(appId))
            .and(CORE_SCAN_COLLECTION.ID.eq(id))
            .and(CORE_SCAN_COLLECTION.DELETED_AT.isNull)
            .fetchOne()
        return record?.let { toModel(it as org.jooq.Record) }
    }

    companion object {
        fun toModel(r: org.jooq.Record): ScanCollection = ScanCollection(
            id = r.get(CORE_SCAN_COLLECTION.ID)!!,
            appId = r.get(CORE_SCAN_COLLECTION.APP_ID)!!,
            installId = r.get(CORE_SCAN_COLLECTION.INSTALL_ID),
            userId = r.get(CORE_SCAN_COLLECTION.USER_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() },
            isDefault = r.get(CORE_SCAN_COLLECTION.IS_DEFAULT) ?: false,
            createdAt = r.get(CORE_SCAN_COLLECTION.CREATED_AT)!!,
            updatedAt = r.get(CORE_SCAN_COLLECTION.UPDATED_AT),
            deletedAt = r.get(CORE_SCAN_COLLECTION.DELETED_AT),
        )
    }
}
