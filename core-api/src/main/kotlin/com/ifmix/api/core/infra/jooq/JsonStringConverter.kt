package com.ifmix.api.core.infra.jooq

import org.jooq.Converter
import org.jooq.JSONB

/**
 * jOOQ Converter: DB JSONB ↔ String。
 * 用于只需要存/取原始 JSON 文本的列（raw_payload, raw_response 等）。
 */
class JsonStringConverter : Converter<JSONB, String> {
    override fun from(databaseObject: JSONB?): String? = databaseObject?.data()
    override fun to(userObject: String?): JSONB? = userObject?.let { JSONB.jsonb(it) }
    override fun fromType(): Class<JSONB> = JSONB::class.java
    override fun toType(): Class<String> = String::class.java
}
