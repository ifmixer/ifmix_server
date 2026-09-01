package com.ifmix.api.core.infra.config

import com.ifmix.api.core.modules.customer.AnonymousCleanupConfig
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 启用 Spring 定时任务（阶段 6 匿名 Customer 清理 @Scheduled）。
 * 同时注册清理配置属性 [AnonymousCleanupConfig]。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AnonymousCleanupConfig::class)
class SchedulingConfig
