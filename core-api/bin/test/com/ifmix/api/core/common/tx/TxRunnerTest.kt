package com.ifmix.api.core.common.tx

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.TodoDocument
import com.ifmix.api.core.support.AbstractMongoTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

class TxRunnerTest : AbstractMongoTest() {

    private val ctx = RequestContext(appId = "app-tx")

    @Autowired
    lateinit var txRunner: TxRunner

    @BeforeEach
    fun ensureCollection() {
        // 事务中隐式建集合在部分版本会失败，测试前先确保集合存在
        if (!mongoTemplate.collectionExists(TodoDocument::class.java)) {
            mongoTemplate.createCollection(TodoDocument::class.java)
        }
    }

    private fun todo(t: String) = TodoDocument().apply {
        title = t
        appId = "app-tx"
        createdAt = Instant.now()
        updatedAt = Instant.now()
    }

    @Test
    fun commitPersistsAllWrites() {
        txRunner.withTx(ctx) {
            mongoTemplate.insert(todo("a"))
            mongoTemplate.insert(todo("b"))
        }
        assertThat(mongoTemplate.findAll(TodoDocument::class.java)).hasSize(2)
    }

    @Test
    fun rollbackDiscardsAllWrites() {
        assertThatThrownBy {
            txRunner.withTx<Unit>(ctx) {
                mongoTemplate.insert(todo("a"))
                throw RuntimeException("boom")
            }
        }.isInstanceOf(RuntimeException::class.java)

        assertThat(mongoTemplate.findAll(TodoDocument::class.java)).isEmpty()
    }
}
