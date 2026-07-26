package com.ifmix.api.core.common.service

import com.ifmix.api.core.common.db.CRUDRepository
import com.ifmix.api.core.common.db.BaseAppDocument
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.TodoDocument
import org.assertj.core.api.Assertions.assertThat
import org.bson.types.ObjectId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.mongodb.core.MongoTemplate
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class CRUDServiceTest {

    @Mock
    private lateinit var mongo: MongoTemplate

    private val ctx = RequestContext(appId = "app-9")
    private lateinit var service: CRUDService<TodoDocument>

    @BeforeEach
    fun init() {
        service = CRUDService(CRUDRepository(mongo, TodoDocument::class.java))
    }

    @Test
    fun createOneStampsTenantAndTimestamps() {
        val id = ObjectId().toHexString()
        whenever(mongo.insert(any<BaseAppDocument>())).thenAnswer { invocation ->
            invocation.getArgument<BaseAppDocument>(0).apply { this.id = id }
        }

        val doc = TodoDocument().apply { title = "t" }
        val generatedId = service.createOne(ctx, doc)

        assertThat(generatedId).isEqualTo(id)
        assertThat(doc.appId).isEqualTo("app-9")
        assertThat(doc.createdAt).isNotNull
        assertThat(doc.updatedAt).isNotNull
        assertThat(doc.deletedAt).isNull()
    }

    @Test
    fun createOneClearsDeletedAtOnNewEntity() {
        val id = ObjectId().toHexString()
        whenever(mongo.insert(any<BaseAppDocument>())).thenAnswer { invocation ->
            invocation.getArgument<BaseAppDocument>(0).apply { this.id = id }
        }

        val doc = TodoDocument().apply {
            title = "t"
            deletedAt = Instant.now().minusSeconds(3600)
        }
        service.createOne(ctx, doc)

        assertThat(doc.deletedAt).isNull()
    }
}
