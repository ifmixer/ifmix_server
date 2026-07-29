package com.ifmix.api.core.infra.config

import org.springframework.context.annotation.Configuration

/**
 * 事务管理：Jimmer + PostgreSQL 使用 Spring 的 PlatformTransactionManager（由 spring-boot-starter-jdbc 自动配置）。
 * 此文件保留为空配置占位，如需自定义事务属性可在此扩展。
 */
@Configuration
class TransactionConfig
