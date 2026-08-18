package com.ifmix.api.core.modules.ai.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.jooq.tables.CoreAgnesKey.Companion.CORE_AGNES_KEY
import com.ifmix.api.core.model.ai.AgnesKey
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * AgnesKey repository.
 */
@Repository
class AgnesKeyRepository {

    fun findAllEnabled(ctx: SvcCtx): List<AgnesKey> =
        ctx.dsl.selectFrom(CORE_AGNES_KEY)
            .where(CORE_AGNES_KEY.DELETED_AT.isNull)
            .fetch()
            .map { mapToModel(it) }

    fun findAvailable(ctx: SvcCtx, appId: UUID): List<AgnesKey> {
        val now = Instant.now()
        return ctx.dsl.selectFrom(CORE_AGNES_KEY)
            .where(CORE_AGNES_KEY.APP_ID.eq(appId))
            .and(
                CORE_AGNES_KEY.UNAVAILABLE_UNTIL.isNull
                    .or(CORE_AGNES_KEY.UNAVAILABLE_UNTIL.lt(now))
            )
            .and(CORE_AGNES_KEY.DELETED_AT.isNull)
            .fetch()
            .map { mapToModel(it) }
    }

    fun markUnavailable(ctx: SvcCtx, keyId: UUID, until: Instant) {
        val now = Instant.now()
        ctx.dsl.update(CORE_AGNES_KEY)
            .set(CORE_AGNES_KEY.UNAVAILABLE_UNTIL, until)
            .set(CORE_AGNES_KEY.UPDATED_AT, now)
            .where(CORE_AGNES_KEY.ID.eq(keyId))
            .execute()
    }

    private fun mapToModel(record: com.ifmix.api.core.jooq.tables.records.CoreAgnesKeyRecord): AgnesKey {
        return AgnesKey(
            id = record.id!!,
            appId = record.appId!!,
            key = record.key,
            email = record.email,
            type = record.type ?: 0,
            rateLimit = record.rateLimit ?: 0L,
            windowSec = record.windowSec ?: 0L,
            models = record.models,
            unavailableUntil = record.unavailableUntil,
            createdAt = record.createdAt ?: java.time.Instant.now(),
            updatedAt = record.updatedAt,
            deletedAt = record.deletedAt,
        )
    }
}
