package com.ifmix.api.core.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.MongoTransactionManager

/** 注册 Mongo 事务管理器（需副本集）。 */
@Configuration
class TransactionConfig {

    @Bean
    fun mongoTransactionManager(factory: MongoDatabaseFactory): MongoTransactionManager =
        MongoTransactionManager(factory)
}
