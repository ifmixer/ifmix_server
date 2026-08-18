package com.ifmix.api.core.modules.ai.repo

import com.ifmix.api.core.generated.types.ScanUnsetField
import com.ifmix.api.core.generated.types.UpdateScanInput
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOps
import com.ifmix.api.core.jooq.tables.CoreScanRecord.Companion.CORE_SCAN_RECORD
import com.ifmix.api.core.entity.ImageRef
import com.ifmix.api.core.entity.ai.ScanRecord
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

@Repository
class ScanRecordRepository(private val crud: CrudRepoOps) {

    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): ScanRecord? {
        val record = ctx.dsl.selectFrom(CORE_SCAN_RECORD)
            .where(CORE_SCAN_RECORD.APP_ID.eq(appId))
            .and(CORE_SCAN_RECORD.ID.eq(id))
            .and(CORE_SCAN_RECORD.DELETED_AT.isNull)
            .fetchOne()
        return record?.let { toModel(it) }
    }

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

    fun insert(ctx: SvcCtx, record: ScanRecord) {
        val imageKeysJsonb = JSONB.jsonb(mapper.writeValueAsString(record.imageKeys))
        val resultJsonb = record.result?.let { JSONB.jsonb(mapper.writeValueAsString(it)) }
        ctx.dsl.insertInto(
            CORE_SCAN_RECORD,
            CORE_SCAN_RECORD.ID,
            CORE_SCAN_RECORD.APP_ID,
            CORE_SCAN_RECORD.IMAGE_KEYS,
            CORE_SCAN_RECORD.RESULT_JSON,
            CORE_SCAN_RECORD.STATUS,
            CORE_SCAN_RECORD.CLIENT_IP,
            CORE_SCAN_RECORD.LANG,
            CORE_SCAN_RECORD.COUNTRY,
            CORE_SCAN_RECORD.CURRENCY,
            CORE_SCAN_RECORD.USER_DISPLAY_NAME,
            CORE_SCAN_RECORD.USER_NOTES,
            CORE_SCAN_RECORD.COLLECTED,
            CORE_SCAN_RECORD.CREATED_AT,
            CORE_SCAN_RECORD.UPDATED_AT,
            CORE_SCAN_RECORD.DELETED_AT,
        )
            .values(
                record.id,
                record.appId,
                imageKeysJsonb,
                resultJsonb,
                record.status,
                record.clientIp,
                record.lang,
                record.country,
                record.currency,
                record.userDisplayName,
                record.userNotes,
                record.collected,
                record.createdAt,
                record.updatedAt,
                record.deletedAt,
            )
            .execute()
    }

    fun partialUpdate(ctx: SvcCtx, appId: UUID, id: UUID, req: UpdateScanInput) {
        crud.partialUpdate(ctx, CORE_SCAN_RECORD, CORE_SCAN_RECORD.APP_ID, CORE_SCAN_RECORD.ID, appId, id) {
            req.set?.userDisplayName?.let { set(CORE_SCAN_RECORD.USER_DISPLAY_NAME, it) }
            req.set?.userNotes?.let { set(CORE_SCAN_RECORD.USER_NOTES, it) }
            req.set?.collected?.let { set(CORE_SCAN_RECORD.COLLECTED, it) }
            if (req.unset?.contains(ScanUnsetField.USER_DISPLAY_NAME) == true) setNull(CORE_SCAN_RECORD.USER_DISPLAY_NAME)
            if (req.unset?.contains(ScanUnsetField.USER_NOTES) == true) setNull(CORE_SCAN_RECORD.USER_NOTES)
        }
    }

    fun deleteById(ctx: SvcCtx, appId: UUID, id: UUID): Boolean =
        crud.deleteById(ctx, CORE_SCAN_RECORD, CORE_SCAN_RECORD.APP_ID, CORE_SCAN_RECORD.ID, appId, id, CORE_SCAN_RECORD.DELETED_AT)

    fun exists(ctx: SvcCtx, appId: UUID, id: UUID): Boolean =
        crud.exists(ctx, CORE_SCAN_RECORD, CORE_SCAN_RECORD.APP_ID, CORE_SCAN_RECORD.ID, appId, id, CORE_SCAN_RECORD.DELETED_AT)

    // =========================================================================
    // JSON ↔ model helpers
    // =========================================================================

    private fun toModel(r: org.jooq.Record): ScanRecord = ScanRecord(
        id = r.get(CORE_SCAN_RECORD.ID)!!,
        appId = r.get(CORE_SCAN_RECORD.APP_ID)!!,
        imageKeys = parseImageKeys(r.get(CORE_SCAN_RECORD.IMAGE_KEYS)),
        result = parseResult(r.get(CORE_SCAN_RECORD.RESULT_JSON)),
        status = r.get(CORE_SCAN_RECORD.STATUS) ?: 100,
        clientIp = r.get(CORE_SCAN_RECORD.CLIENT_IP),
        lang = r.get(CORE_SCAN_RECORD.LANG),
        country = r.get(CORE_SCAN_RECORD.COUNTRY),
        currency = r.get(CORE_SCAN_RECORD.CURRENCY),
        userDisplayName = r.get(CORE_SCAN_RECORD.USER_DISPLAY_NAME),
        userNotes = r.get(CORE_SCAN_RECORD.USER_NOTES),
        collected = r.get(CORE_SCAN_RECORD.COLLECTED) ?: false,
        createdAt = r.get(CORE_SCAN_RECORD.CREATED_AT) ?: Instant.now(),
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

    private fun parseResult(jsonb: JSONB?): Any? =
        jsonb?.toString()?.let { str ->
            runCatching { mapper.readValue(str, Any::class.java) }
                .getOrNull()
        }

    companion object {
        private val mapper = jacksonObjectMapper()
    }
}
