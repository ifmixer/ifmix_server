package com.ifmix.api.core.common.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper
import org.springframework.data.mongodb.core.convert.MappingMongoConverter

/** 去掉文档里的 _class 类型提示，保持存储整洁。 */
@Configuration
class MongoConfig(private val env: Environment) {

    private val log = LoggerFactory.getLogger(MongoConfig::class.java)

    @Autowired
    fun removeTypeHint(converter: MappingMongoConverter) {
        converter.setTypeMapper(DefaultMongoTypeMapper(null))
    }

    @EventListener(ApplicationReadyEvent::class)
    fun logMongoConnection() {
        val uri = env.getProperty("spring.mongodb.uri") ?: "unknown"
        // 脱敏：隐藏密码部分
        val safeUri = uri.replace(Regex("://([^:]+):([^@]+)@"), "://\$1:***@")
        log.info("MongoDB connected → {}", safeUri)
    }
}
