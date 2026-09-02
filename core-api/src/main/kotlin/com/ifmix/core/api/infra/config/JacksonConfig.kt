package com.ifmix.core.api.infra.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.JacksonModule
import tools.jackson.module.kotlin.KotlinModule

/** 全局 Jackson 3 定制。 */
@Configuration
class JacksonConfig {

    /** Jimmer entity 序列化支持：跳过未加载字段，不抛 UnloadedException */
    @Bean
    fun jimmerImmutableModule(): JacksonModule =
        org.babyfish.jimmer.jackson.v3.ImmutableModuleV3()

    @Bean
    fun kotlinModule(): KotlinModule = KotlinModule.Builder().build()

    /**
     * snake_case 命名的 ObjectMapper，用于 AI 模型 JSON 交互。
     */
    @Bean("snakeCaseMapper")
    fun snakeCaseMapper(): tools.jackson.databind.ObjectMapper =
        tools.jackson.databind.json.JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
            .build()
}
