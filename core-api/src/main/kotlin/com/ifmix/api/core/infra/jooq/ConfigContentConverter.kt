package com.ifmix.api.core.infra.jooq

import com.ifmix.api.core.entity.app.ConfigContent
import org.jooq.Converter
import org.jooq.JSONB
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * jOOQ Converter: DB JSONB ↔ ConfigContent。
 * 注册到 codegen forcedTypes，使 CORE_APP_CONFIG_REVISION.CONTENT 字段直接映射为 ConfigContent。
 */
class ConfigContentConverter : Converter<JSONB, ConfigContent> {

    private val mapper = jacksonObjectMapper()

    override fun from(databaseObject: JSONB?): ConfigContent =
        databaseObject?.data()
            ?.let { runCatching { mapper.readValue(it, ConfigContent::class.java) }.getOrNull() }
            ?: ConfigContent()

    override fun to(userObject: ConfigContent?): JSONB? =
        userObject?.let { JSONB.jsonb(mapper.writeValueAsString(it)) }

    override fun fromType(): Class<JSONB> = JSONB::class.java
    override fun toType(): Class<ConfigContent> = ConfigContent::class.java
}
