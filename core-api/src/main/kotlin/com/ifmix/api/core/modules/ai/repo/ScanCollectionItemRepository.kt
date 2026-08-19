package com.ifmix.api.core.modules.ai.repo

import com.ifmix.api.core.entity.ai.ScanCollectionItem
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.dto.Page
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.lt
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class ScanCollectionItemRepository(sql: KSqlClient) : BaseAppCrudRepository<ScanCollectionItem>(sql, ScanCollectionItem::class) {

    fun insertIfAbsent(ctx: SvcCtx, appId: UUID, collectionId: UUID, scanRecordId: UUID): UUID {
        val existing = sql.createQuery(ScanCollectionItem::class) {
            where(table.appId eq appId)
            where(table.collectionId eq collectionId)
            where(table.scanRecordId eq scanRecordId)
            select(table)
        }.limit(1).execute().firstOrNull()

        if (existing != null) return existing.id

        val id = UuidV7.generate()
        val entity = ScanCollectionItem {
            id = id
            this.appId = appId
            this.collectionId = collectionId
            this.scanRecordId = scanRecordId
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }
        save(ctx, entity)
        return id
    }

    fun softDeleteByScanIds(ctx: SvcCtx, appId: UUID, collectionId: UUID, scanRecordIds: List<UUID>): Int {
        if (scanRecordIds.isEmpty()) return 0
        return sql.createUpdate(ScanCollectionItem::class) {
            where(table.appId eq appId)
            where(table.collectionId eq collectionId)
            where(table.scanRecordId valueIn scanRecordIds)
            set(table.deletedAt, Instant.now())
        }.execute()
    }

    fun findItemsByCursor(ctx: SvcCtx, appId: UUID, collectionId: UUID, limit: Int, cursor: UUID?): Page<ScanCollectionItem> {
        val items = sql.createQuery(ScanCollectionItem::class) {
            where(table.appId eq appId)
            where(table.collectionId eq collectionId)
            cursor?.let { where(table.id lt it) }
            orderBy(table.id.desc())
            select(table)
        }.limit(limit + 1).execute()

        return Page.of(items, limit) { it.id.toString() }
    }

    fun existsByScanRecordId(ctx: SvcCtx, collectionId: UUID, scanRecordId: UUID): Boolean {
        return sql.createQuery(ScanCollectionItem::class) {
            where(table.collectionId eq collectionId)
            where(table.scanRecordId eq scanRecordId)
            select(table)
        }.limit(1).execute().isNotEmpty()
    }
}
