package com.ifmix.api.core.modules.ai.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOpsFactory
import com.ifmix.api.core.jooq.tables.CoreScanCollection.Companion.CORE_SCAN_COLLECTION
import com.ifmix.api.core.entity.ai.ScanCollection
import org.jooq.TableField
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ScanCollectionRepository(factory: CrudRepoOpsFactory) {

    companion object {
        val FIELD_MAP: Map<String, TableField<*, *>> = mapOf(
            ScanCollection::id.name to CORE_SCAN_COLLECTION.ID,
            ScanCollection::appId.name to CORE_SCAN_COLLECTION.APP_ID,
            ScanCollection::installId.name to CORE_SCAN_COLLECTION.INSTALL_ID,
            ScanCollection::userId.name to CORE_SCAN_COLLECTION.USER_ID,
            ScanCollection::isDefault.name to CORE_SCAN_COLLECTION.IS_DEFAULT,
            ScanCollection::createdAt.name to CORE_SCAN_COLLECTION.CREATED_AT,
            ScanCollection::updatedAt.name to CORE_SCAN_COLLECTION.UPDATED_AT,
        )
    }

    private val crud = factory.create(
        table = CORE_SCAN_COLLECTION,
        idField = CORE_SCAN_COLLECTION.ID,
        appIdField = CORE_SCAN_COLLECTION.APP_ID,
        type = ScanCollection::class.java,
        deletedAtField = CORE_SCAN_COLLECTION.DELETED_AT,
    )

    fun findDefault(ctx: SvcCtx, appId: UUID, installId: UUID?, userId: UUID?): ScanCollection? {
        // If userId is provided, prefer user-scoped default collection.
        if (userId != null) {
            val result = ctx.dsl.selectFrom(CORE_SCAN_COLLECTION)
                .where(CORE_SCAN_COLLECTION.APP_ID.eq(appId))
                .and(CORE_SCAN_COLLECTION.IS_DEFAULT.eq(true))
                .and(CORE_SCAN_COLLECTION.USER_ID.eq(userId.toString()))
                .and(CORE_SCAN_COLLECTION.DELETED_AT.isNull)
                .fetchOneInto(ScanCollection::class.java)
            if (result != null) return result
        }
        // Fall back to install-scoped default collection.
        if (installId != null) {
            val result = ctx.dsl.selectFrom(CORE_SCAN_COLLECTION)
                .where(CORE_SCAN_COLLECTION.APP_ID.eq(appId))
                .and(CORE_SCAN_COLLECTION.IS_DEFAULT.eq(true))
                .and(CORE_SCAN_COLLECTION.INSTALL_ID.eq(installId))
                .and(CORE_SCAN_COLLECTION.DELETED_AT.isNull)
                .fetchOneInto(ScanCollection::class.java)
            if (result != null) return result
        }
        return null
    }

    fun insert(ctx: SvcCtx, collection: ScanCollection) {
        crud.insert(ctx, collection)
    }

    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): ScanCollection? =
        ctx.dsl.selectFrom(CORE_SCAN_COLLECTION)
            .where(CORE_SCAN_COLLECTION.APP_ID.eq(appId))
            .and(CORE_SCAN_COLLECTION.ID.eq(id))
            .and(CORE_SCAN_COLLECTION.DELETED_AT.isNull)
            .fetchOneInto(ScanCollection::class.java)
}
