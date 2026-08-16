package com.ifmix.api.core.infra.exposed

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration

/**
 * Exposed ORM 配置。通过 `exposed.enabled=true` 开启。
 *
 * Spring Boot Starter 自动配置会通过 @ConditionalOnProperty 控制激活，
 * 无需手动连接 Database — SpringTransactionManager 会自动桥接 Spring 事务到 Exposed。
 */
@Configuration
@ConditionalOnProperty(name = ["exposed.enabled"], havingValue = "true", matchIfMissing = false)
class ExposedConfig
