package com.ifmix.api.core.infra.jooq

import org.jooq.Converter
import org.jooq.JSONB
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * jOOQ Converter: DB JSONB ↔ Map<String, Any?>。
 * 通用 JSON 对象映射，用于 basic_result / premium_result 等任意 JSON 列。
 */
class JsonMapConverter : Converter<JSONB, Map<String, Any?>> {

    private val mapper = jacksonObjectMapper()

    @Suppress("UNCHECKED_CAST")
    override fun from(databaseObject: JSONB?): Map<String, Any?>? =
        databaseObject?.data()
            ?.let { runCatching { mapper.readValue(it, Map::class.java) as Map<String, Any?> }.getOrNull() }

    override fun to(userObject: Map<String, Any?>?): JSONB? =
        userObject?.let { JSONB.jsonb(mapper.writeValueAsString(it)) }

    override fun fromType(): Class<JSONB> = JSONB::class.java

    @Suppress("UNCHECKED_CAST")
    override fun toType(): Class<Map<String, Any?>> = Map::class.java as Class<Map<String, Any?>>
}
