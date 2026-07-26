package com.ifmix.api.core.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JacksonModule
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant

/** 全局 Jackson 3 定制：Instant 出站为 epoch 毫秒整数，入站从 epoch 毫秒解析。 */
@Configuration
class JacksonConfig {

    @Bean
    fun instantEpochMillisModule(): JacksonModule = tools.jackson.databind.module.SimpleModule("InstantEpochMillis").apply {
        addSerializer(Instant::class.java, object : ValueSerializer<Instant>() {
            override fun serialize(value: Instant, gen: JsonGenerator, ctx: SerializationContext) {
                gen.writeNumber(value.toEpochMilli())
            }
        })
        addDeserializer(Instant::class.java, object : ValueDeserializer<Instant>() {
            override fun deserialize(p: JsonParser, ctx: DeserializationContext): Instant =
                Instant.ofEpochMilli(p.longValue)
        })
    }

    @Bean
    fun kotlinModule(): KotlinModule = KotlinModule.Builder().build()
}
