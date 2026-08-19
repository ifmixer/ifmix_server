package com.ifmix.api.core.infra.jooq

import com.ifmix.api.core.entity.ImageRef
import org.jooq.Converter
import org.jooq.JSONB
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * jOOQ Converter: DB JSONB ↔ List<ImageRef>。
 * 用于 core_scan_record.image_keys 列。
 */
class ImageRefListConverter : Converter<JSONB, List<ImageRef>> {

    private val mapper = jacksonObjectMapper()

    override fun from(databaseObject: JSONB?): List<ImageRef> =
        databaseObject?.data()
            ?.let {
                runCatching {
                    val type = mapper.typeFactory.constructCollectionType(List::class.java, ImageRef::class.java)
                    mapper.readValue<List<ImageRef>>(it, type)
                }.getOrNull()
            }
            ?: emptyList()

    override fun to(userObject: List<ImageRef>?): JSONB? =
        userObject?.let { JSONB.jsonb(mapper.writeValueAsString(it)) }

    override fun fromType(): Class<JSONB> = JSONB::class.java

    @Suppress("UNCHECKED_CAST")
    override fun toType(): Class<List<ImageRef>> = List::class.java as Class<List<ImageRef>>
}
