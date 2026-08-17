package com.ifmix.api.core.infra.jooq

import org.jooq.RecordContext
import org.jooq.RecordListener
import java.time.Instant

class AuditRecordListener : RecordListener {

    override fun insertStart(ctx: RecordContext) {
        val record = ctx.record()
        val now = Instant.now()
        record.field("created_at")?.let { record.set(it as org.jooq.Field<Any?>, now) }
        record.field("updated_at")?.let { record.set(it as org.jooq.Field<Any?>, now) }
    }

    override fun updateStart(ctx: RecordContext) {
        val record = ctx.record()
        record.field("updated_at")?.let { record.set(it as org.jooq.Field<Any?>, Instant.now()) }
    }
}
