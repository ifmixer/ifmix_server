package com.ifmix.api.core.infra.jooq

import org.jooq.Converter
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * jOOQ Converter: DB 的 TIMESTAMPTZ (OffsetDateTime) ↔ Java 的 Instant。
 * 全局应用于所有 TIMESTAMP/TIMESTAMPWITHTIMEZONE 列。
 */
class InstantConverter : Converter<OffsetDateTime, Instant> {
    override fun from(databaseObject: OffsetDateTime?): Instant? = databaseObject?.toInstant()
    override fun to(userObject: Instant?): OffsetDateTime? = userObject?.atOffset(ZoneOffset.UTC)
    override fun fromType(): Class<OffsetDateTime> = OffsetDateTime::class.java
    override fun toType(): Class<Instant> = Instant::class.java
}
