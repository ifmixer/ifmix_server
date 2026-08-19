package com.ifmix.api.core.modules.ai.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.jooq.tables.CoreAgnesKey.Companion.CORE_AGNES_KEY
import com.ifmix.api.core.entity.ai.AgnesKey
import org.jooq.TableField
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * AgnesKey repository.
 */
@Repository
class AgnesKeyRepository {

    companion object {
        val FIELD_MAP: Map<String, TableField<*, *>> = mapOf(
            AgnesKey::id.name to CORE_AGNES_KEY.ID,
            AgnesKey::appId.name to CORE_AGNES_KEY.APP_ID,
            AgnesKey::key.name to CORE_AGNES_KEY.KEY,
            AgnesKey::email.name to CORE_AGNES_KEY.EMAIL,
            AgnesKey::type.name to CORE_AGNES_KEY.TYPE,
            AgnesKey::rateLimit.name to CORE_AGNES_KEY.RATE_LIMIT,
            AgnesKey::windowSec.name to CORE_AGNES_KEY.WINDOW_SEC,
            AgnesKey::unavailableUntil.name to CORE_AGNES_KEY.UNAVAILABLE_UNTIL,
            AgnesKey::createdAt.name to CORE_AGNES_KEY.CREATED_AT,
            AgnesKey::updatedAt.name to CORE_AGNES_KEY.UPDATED_AT,
        )
    }

    fun findAllEnabled(ctx: SvcCtx): List<AgnesKey> =
        ctx.dsl.selectFrom(CORE_AGNES_KEY)
            .where(CORE_AGNES_KEY.DELETED_AT.isNull)
            .fetchInto(AgnesKey::class.java)

    fun findAvailable(ctx: SvcCtx, appId: UUID): List<AgnesKey> {
        val now = Instant.now()
        return ctx.dsl.selectFrom(CORE_AGNES_KEY)
            .where(CORE_AGNES_KEY.APP_ID.eq(appId))
            .and(
                CORE_AGNES_KEY.UNAVAILABLE_UNTIL.isNull
                    .or(CORE_AGNES_KEY.UNAVAILABLE_UNTIL.lt(now))
            )
            .and(CORE_AGNES_KEY.DELETED_AT.isNull)
            .fetchInto(AgnesKey::class.java)
    }

    fun markUnavailable(ctx: SvcCtx, keyId: UUID, until: Instant) {
        val now = Instant.now()
        ctx.dsl.update(CORE_AGNES_KEY)
            .set(CORE_AGNES_KEY.UNAVAILABLE_UNTIL, until)
            .set(CORE_AGNES_KEY.UPDATED_AT, now)
            .where(CORE_AGNES_KEY.ID.eq(keyId))
            .execute()
    }
}
