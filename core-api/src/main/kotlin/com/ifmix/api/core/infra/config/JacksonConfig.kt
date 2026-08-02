package com.ifmix.api.core.infra.config

import com.ifmix.api.core.infra.codec.toBase58
import com.ifmix.api.core.infra.codec.toUuidFromBase58
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
import java.util.UUID

/** 全局 Jackson 3 定制。 */
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

    /** UUID ↔ Base58 (22 chars, URL-safe) */
    @Bean
    fun uuidBase58Module(): JacksonModule = tools.jackson.databind.module.SimpleModule("UuidBase58").apply {
        addSerializer(UUID::class.java, object : ValueSerializer<UUID>() {
            override fun serialize(value: UUID, gen: JsonGenerator, ctx: SerializationContext) {
                gen.writeString(value.toBase58())
            }
        })
        addDeserializer(UUID::class.java, object : ValueDeserializer<UUID>() {
            override fun deserialize(p: JsonParser, ctx: DeserializationContext): UUID =
                p.text.toUuidFromBase58()
        })
    }

    @Bean
    fun kotlinModule(): KotlinModule = KotlinModule.Builder().build()

    /**
     * snake_case 命名的 ObjectMapper，用于 AI 模型 JSON 交互。
     * 注意：不注册 UUID Base58 模块（AI 模型不用 base58 UUID）。
     */
    @Bean("snakeCaseMapper")
    fun snakeCaseMapper(): tools.jackson.databind.ObjectMapper =
        tools.jackson.databind.json.JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
            .build()
}
