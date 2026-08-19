package com.ifmix.api.core.modules.ai.repo

import com.ifmix.api.core.common.db.BaseEntity
import com.ifmix.api.core.common.db.CRUDOps
import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.db.RepoCtx
import com.ifmix.api.core.common.http.RequestContext
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.isEqualTo
import java.time.Instant
import com.ifmix.api.core.modules.ai.entity.ScanRecordEntity
import com.ifmix.api.core.modules.ai.repo.ScanRecordRepository

/**
 * 扫描记录仓储。
 *
 * 持有 CRUDOps 实例获得基础 CRUD + 游标分页，同时持有 MongoTemplate 用于自定义操作。
 */
class ScanRecordRepository(
    private val crudOps: CRUDOps<ScanRecordEntity>,
    private val mongo: MongoTemplate,
) {
    /** 按 id 查询（不软删过滤）。 */
    fun findById(id: String): ScanRecordEntity? =
        crudOps.mongo.findOne(
            Query(Criteria().andOperator(ScanRecordEntity::id isEqualTo ObjectId(id))),
            ScanRecordEntity::class.java,
        )

    /** 按 id 列表批量查询（带 appId 过滤和软删）。 */
    fun findByIds(ctx: RequestContext, ids: List<String>): List<ScanRecordEntity> =
        crudOps.findByIds(RepoCtx(), ctx.appId, ids)

    /** 按游标分页查询扫描记录（带 appId 过滤和软删）。 */
    fun findByCursor(ctx: RequestContext, input: CursorQueryInput = CursorQueryInput()): Page<ScanRecordEntity> =
        crudOps.findByCursor(RepoCtx(), ctx.appId, input)

    /** 软删扫描记录。 */
    fun deleteById(ctx: RequestContext, id: String): Boolean {
        val query = Query(
            Criteria().andOperator(
                ScanRecordEntity::id isEqualTo ObjectId(id),
                ScanRecordEntity::appId isEqualTo ctx.appId,
                ScanRecordEntity::deletedAt isEqualTo null,
            ),
        )
        val update = Update()
            .set(ScanRecordEntity::deletedAt, Instant.now())
            .set(BaseEntity::updatedAt, Instant.now())
        return mongo.updateFirst(query, update, ScanRecordEntity::class.java).modifiedCount > 0
    }
}
