package com.ifmix.api.core.infra.mybatis

import tools.jackson.module.kotlin.jacksonObjectMapper

object JsonbUtil {
    private val mapper = jacksonObjectMapper()

    fun serialize(value: Any?): String? = value?.let { mapper.writeValueAsString(it) }

    fun <T> deserialize(json: String?, type: Class<T>): T? =
        json?.let { mapper.readValue(it, type) }
}
