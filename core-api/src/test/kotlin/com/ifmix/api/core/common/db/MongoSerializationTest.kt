package com.ifmix.api.core.infra.db

import com.ifmix.api.core.service.todo.TodoDocument
import com.ifmix.api.core.support.AbstractMongoTest
import org.assertj.core.api.Assertions.assertThat
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date

class MongoSerializationTest : AbstractMongoTest() {

    @Test
    fun storesObjectIdAndDateWithoutClassHint() {
        val doc = TodoDocument().apply {
            title = "hello"
            appId = "app-1"
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }

        mongoTemplate.insert(doc)

        assertThat(doc.id).isNotNull().hasSize(24)

        val raw = mongoTemplate.getCollection("todos").find().first()
        assertThat(raw).isNotNull
        assertThat(raw!!["_id"]).isInstanceOf(ObjectId::class.java)
        assertThat(raw.containsKey("_class")).isFalse()
        assertThat(raw["createdAt"]).isInstanceOf(Date::class.java)

        val loaded = mongoTemplate.findById(doc.id!!, TodoDocument::class.java)
        assertThat(loaded?.title).isEqualTo("hello")
    }
}
