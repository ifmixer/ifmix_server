package com.ifmix.api.core.support

import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.mongodb.core.MongoTemplate
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** 集成测试基类：启动真实 MongoDB（单节点副本集），每个测试后清库。 */
@SpringBootTest
@Testcontainers
abstract class AbstractMongoTest {

    @Autowired
    protected lateinit var mongoTemplate: MongoTemplate

    @AfterEach
    fun dropDatabase() {
        mongoTemplate.db.drop()
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8.0")
    }
}
