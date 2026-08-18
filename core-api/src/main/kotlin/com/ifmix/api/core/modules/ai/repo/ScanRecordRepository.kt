package com.ifmix.api.core.modules.ai.repo

import com.ifmix.api.core.generated.types.ScanUnsetField
import com.ifmix.api.core.generated.types.UpdateScanInput
import com.ifmix.api.core.generated.types.FilterGroup
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOpsFactory
import com.ifmix.api.core.infra.jooq.FilterConditionParser
import com.ifmix.api.core.jooq.tables.CoreScanRecord.Companion.CORE_SCAN_RECORD
import com.ifmix.api.core.entity.ImageRef
import com.ifmix.api.core.entity.ai.ScanRecord
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class ScanRecordRepository(factory: CrudRepoOpsFactory) {

    private val crud = factory.create(
        table = CORE_SCAN_RECORD,
        idField = CORE_SCAN_RECORD.ID,
        appIdField = CORE_SCAN_RECORD.APP_ID,
        type = ScanRecord::class.java,
        deletedAtField = CORE_SCAN_RECORD.DELETED_AT,
    )

    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): ScanRecord? =
        ctx.dsl.selectFrom(CORE_SCAN_RECORD)
            .where(CORE_SCAN_RECORD.APP_ID.eq(appId))
            .and(CORE_SCAN_RECORD.ID.eq(id))
            .and(CORE_SCAN_RECORD.DELETED_AT.isNull)
            .fetchOne()?.let { toModel(it) }

    fun findByIds(ctx: SvcCtx, ids: Collection<UUID>): List<ScanRecord> {
        if (ids.isEmpty()) return emptyList()
        return ctx.dsl.selectFrom(CORE_SCAN_RECORD)
            .where(CORE_SCAN_RECORD.ID.`in`(ids))
            .fetch()
            .map { toModel(it) }
    }

    fun findByCursor(ctx: SvcCtx, appId: UUID, collected: Boolean?, cursor: UUID?, limit: Int): List<ScanRecord> {
        var cond = CORE_SCAN_RECORD.APP_ID.eq(appId).and(CORE_SCAN_RECORD.DELETED_AT.isNull)
        collected?.let { cond = cond.and(CORE_SCAN_RECORD.COLLECTED.eq(it)) }
        cursor?.let { cond = cond.and(CORE_SCAN_RECORD.ID.lt(it)) }
        return ctx.dsl.selectFrom(CORE_SCAN_RECORD)
            .where(cond)
            .orderBy(CORE_SCAN_RECORD.ID.desc())
            .limit(limit)
            .fetch()
            .map { toModel(it) }
    }

    fun insert(ctx: SvcCtx, entity: ScanRecord) {
        val record = ctx.dsl.newRecord(CORE_SCAN_RECORD, entity)
        record.imageKeys = JSONB.jsonb(mapper.writeValueAsString(entity.imageKeys))
        record.basicResult = entity.basicResult?.let { JSONB.jsonb(mapper.writeValueAsString(it)) }
        record.premiumResult = entity.premiumResult?.let { JSONB.jsonb(mapper.writeValueAsString(it)) }
        ctx.dsl.executeInsert(record)
    }

    fun partialUpdate(ctx: SvcCtx, appId: UUID, id: UUID, req: UpdateScanInput) {
        crud.partialUpdate(ctx, appId, id) {
            req.set?.userDisplayName?.let { set(CORE_SCAN_RECORD.USER_DISPLAY_NAME, it) }
            req.set?.userNotes?.let { set(CORE_SCAN_RECORD.USER_NOTES, it) }
            req.set?.collected?.let { set(CORE_SCAN_RECORD.COLLECTED, it) }
            if (req.unset?.contains(ScanUnsetField.USER_DISPLAY_NAME) == true) setNull(CORE_SCAN_RECORD.USER_DISPLAY_NAME)
            if (req.unset?.contains(ScanUnsetField.USER_NOTES) == true) setNull(CORE_SCAN_RECORD.USER_NOTES)
        }
    }

    fun deleteById(ctx: SvcCtx, appId: UUID, id: UUID): Boolean = crud.deleteById(ctx, appId, id)
    fun exists(ctx: SvcCtx, appId: UUID, id: UUID): Boolean = crud.exists(ctx, appId, id)

    // ===== 动态 Filter 查询 =====

    private val filterParser = FilterConditionParser(
        table = CORE_SCAN_RECORD,
        allowedFields = setOf(
            "status", "collected", "lang", "country", "currency",
            "user_display_name", "user_notes", "created_at", "updated_at",
        ),
    )

    @Suppress("UNCHECKED_CAST")
    fun findByFilter(ctx: SvcCtx, appId: UUID, filter: FilterGroup?, cursor: UUID?, limit: Int): List<ScanRecord> {
        val filterMap = filter?.let { convertFilterGroupToMap(it) }
        val dynamicCond = filterParser.parse(filterMap)
        var cond = CORE_SCAN_RECORD.APP_ID.eq(appId)
            .and(CORE_SCAN_RECORD.DELETED_AT.isNull)
            .and(dynamicCond)
        cursor?.let { cond = cond.and(CORE_SCAN_RECORD.ID.lt(it)) }
        return ctx.dsl.selectFrom(CORE_SCAN_RECORD)
            .where(cond)
            .orderBy(CORE_SCAN_RECORD.ID.desc())
            .limit(limit)
            .fetch()
            .map { toModel(it) }
    }

    /**
     * 将 DGS codegen 生成的 FilterGroup 对象转为 Map 结构（FilterConditionParser 的输入格式）。
     */
    @Suppress("UNCHECKED_CAST")
    private fun convertFilterGroupToMap(group: FilterGroup): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        group.and?.let { list ->
            result["and"] = list.map { expr -> convertExprToMap(expr) }
        }
        group.or?.let { list ->
            result["or"] = list.map { expr -> convertExprToMap(expr) }
        }
        return result
    }

    private fun convertExprToMap(expr: com.ifmix.api.core.generated.types.FilterExpr): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        expr.field?.let { f ->
            result["field"] = mapOf(
                "field" to f.field,
                "op" to f.op.name,
                "value" to f.value,
                "values" to f.values,
            )
        }
        expr.group?.let { g ->
            result["group"] = convertFilterGroupToMap(g)
        }
        return result
    }

    // =========================================================================
    // JSON ↔ model helpers (JSONB fields require manual mapping)
    // =========================================================================

    private fun toModel(r: org.jooq.Record): ScanRecord = ScanRecord(
        id = r.get(CORE_SCAN_RECORD.ID)!!,
        appId = r.get(CORE_SCAN_RECORD.APP_ID)!!,
        imageKeys = parseImageKeys(r.get(CORE_SCAN_RECORD.IMAGE_KEYS)),
        basicResult = parseJsonMap(r.get(CORE_SCAN_RECORD.BASIC_RESULT)),
        premiumResult = parseJsonMap(r.get(CORE_SCAN_RECORD.PREMIUM_RESULT)),
        status = r.get(CORE_SCAN_RECORD.STATUS) ?: 100,
        clientIp = r.get(CORE_SCAN_RECORD.CLIENT_IP),
        lang = r.get(CORE_SCAN_RECORD.LANG),
        country = r.get(CORE_SCAN_RECORD.COUNTRY),
        currency = r.get(CORE_SCAN_RECORD.CURRENCY),
        userDisplayName = r.get(CORE_SCAN_RECORD.USER_DISPLAY_NAME),
        userNotes = r.get(CORE_SCAN_RECORD.USER_NOTES),
        collected = r.get(CORE_SCAN_RECORD.COLLECTED) ?: false,
        createdAt = r.get(CORE_SCAN_RECORD.CREATED_AT)!!,
        updatedAt = r.get(CORE_SCAN_RECORD.UPDATED_AT),
        deletedAt = r.get(CORE_SCAN_RECORD.DELETED_AT),
    )

    private fun parseImageKeys(jsonb: JSONB?): List<ImageRef> {
        val str = jsonb?.toString() ?: return emptyList()
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            mapper.readValue(str, List::class.java) as List<ImageRef>
        }.getOrDefault(emptyList())
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJsonMap(jsonb: JSONB?): Map<String, Any?>? =
        jsonb?.toString()?.let { str ->
            runCatching { mapper.readValue(str, Map::class.java) as Map<String, Any?> }
                .getOrNull()
        }

    companion object {
        private val mapper = jacksonObjectMapper()
    }
}
