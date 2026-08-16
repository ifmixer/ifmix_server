package com.ifmix.api.core.modules.collection

import com.ifmix.api.core.common.db.BaseAppDocument
import com.ifmix.api.core.common.db.BaseDocument
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.antique.ScanRecordDocument
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.inValues
import org.springframework.data.mongodb.core.query.isEqualTo
import org.springframework.data.mongodb.core.query.lt
import java.time.Instant

/**
 * collection_item 自定义仓储：幂等插入、批量软删、join scan_record 列表查询。
 *
 * 不使用 CRUDRepository（collectionId 为运行时参数，非 ctx 属性），直接持 MongoTemplate。
 */
class CollectionItemRepository(private val mongo: MongoTemplate) {

    /**
     * 幂等插入收藏条目。若已存在则返回已有 id，否则插入并返回新 id。
     * partial unique 约束兜底并发冲突。
     *
     * @return collection_item 文档的 id
     */
    fun insertIfAbsent(ctx: RequestContext, collectionId: String, scanRecordId: String): String {
        val scanObjId = org.bson.types.ObjectId(scanRecordId)
        val existing = mongo.findOne(
            Query(
                Criteria().andOperator(
                    CollectionItemDocument::scanRecordId isEqualTo scanObjId,
                    CollectionItemDocument::deletedAt isEqualTo null,
                ),
            ),
            CollectionItemDocument::class.java,
        )
        if (existing != null) return existing.id.toHexString()

        val now = Instant.now()
        val doc = CollectionItemDocument().apply {
            appId = ObjectId(ctx.appId)
            this.collectionId = collectionId
            this.scanRecordId = scanObjId
            createdAt = now
            updatedAt = now
        }
        mongo.insert(doc)
        return doc.id.toHexString()
    }

    /**
     * 批量软删指定 scanRecordIds 对应的收藏条目。
     *
     * @return 受影响的条数
     */
    fun softDeleteByScanIds(ctx: RequestContext, collectionId: String, scanRecordIds: List<String>): Long {
        val now = Instant.now()
        val query = Query(
            Criteria().andOperator(
                CollectionItemDocument::appId isEqualTo ctx.appId,
                CollectionItemDocument::deletedAt isEqualTo null,
            ),
        )
        return mongo.updateMulti(
            query,
            Update().set(BaseAppDocument::deletedAt, now).set(BaseDocument::updatedAt, now),
            CollectionItemDocument::class.java,
        ).modifiedCount
    }

    /**
     * 游标分页列出收藏夹中的扫描记录（join scan_record）。
     *
     * 两步查询：
     * 1. 按 item._id desc keyset 分页取未删的 collection_item 的 scanRecordId
     * 2. 批量查 scan_record（appId 分片 + 未删），保持 item 顺序
     *
     * @return (扫描记录列表, 下一页游标)
     */
    fun listScanRecords(
        ctx: RequestContext,
        collectionId: String,
        cursor: String?,
        limit: Int,
    ): Pair<List<ScanRecordDocument>, String?> {
        // 第一步：取该夹未删 item 的 scanRecordId（按 _id keyset 分页）
        val itemQuery = Query(Criteria().andOperator(CollectionItemDocument::deletedAt isEqualTo null))

        // cursor 基于 item._id（ObjectId hex）做 keyset
        if (!cursor.isNullOrBlank() && ObjectId.isValid(cursor)) {
            itemQuery.addCriteria(BaseDocument::id lt ObjectId(cursor))
        }

        itemQuery.with(
            org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC,
                "_id",
            ),
        )
        itemQuery.limit(limit + 1)

        val items = mongo.find(itemQuery, CollectionItemDocument::class.java)
        val hasMore = items.size > limit
        val page = if (hasMore) items.subList(0, limit) else items

        // 收集有效 scanRecordId（ObjectId → hex）
        val scanIds = page
            .mapNotNull { it.scanRecordId }
            .toList()

        if (scanIds.isEmpty()) return Pair(emptyList(), null)

        // 第二步：批量取 scan_record（appId 分片 + 未删），保持 item 顺序
        val scans = mongo.find(
            Query(
                Criteria().andOperator(
                    ScanRecordDocument::appId isEqualTo ctx.appId,
                    ScanRecordDocument::id inValues scanIds,
                    ScanRecordDocument::deletedAt isEqualTo null,
                ),
            ),
            ScanRecordDocument::class.java,
        ).associateBy { it.id.toHexString() }

        val ordered = page.mapNotNull { item ->
            val oid = item.scanRecordId ?: return@mapNotNull null
            scans[oid.toHexString()]
        }
        val nextCursor = if (hasMore) page.last().scanRecordId?.toHexString() else null
        return Pair(ordered, nextCursor)
    }

    private fun base(ctx: RequestContext, collectionId: String): Criteria =
        Criteria().andOperator(
            CollectionItemDocument::appId isEqualTo ctx.appId,
            CollectionItemDocument::collectionId isEqualTo collectionId,
        )

    /**
     * 按游标分页查询收藏条目文档（不带 scan_record 关联）。
     * 用于 GraphQL fetcher 自行关联 scan record。
     */
    fun findItemsByCursor(
        ctx: RequestContext,
        collectionId: String,
        cursor: String?,
        limit: Int,
    ): List<CollectionItemDocument> {
        val query = Query(Criteria().andOperator(CollectionItemDocument::deletedAt isEqualTo null))
        if (!cursor.isNullOrBlank() && ObjectId.isValid(cursor)) {
            query.addCriteria(BaseDocument::id lt ObjectId(cursor))
        }
        query.with(
            org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC,
                "_id",
            ),
        )
        query.limit(limit + 1)
        return mongo.find(query, CollectionItemDocument::class.java)
    }
}
