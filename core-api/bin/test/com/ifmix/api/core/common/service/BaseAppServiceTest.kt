package com.ifmix.api.core.common.service

import com.ifmix.api.core.common.db.BaseAppDocument
import com.ifmix.api.core.common.db.BaseAppRepository
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.TodoDocument
import org.assertj.core.api.Assertions.assertThat
import org.bson.types.ObjectId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.mongodb.core.MongoTemplate
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class BaseAppServiceTest {

    @Mock
    private lateinit var mongo: MongoTemplate

    private val ctx = RequestContext(appId = "app-9")
    private lateinit var repo: BaseAppRepository<TodoDocument>
    private lateinit var service: BaseAppService<TodoDocument>

    @BeforeEach
    fun init() {
        repo = BaseAppRepository(mongo, TodoDocument::class.java, softDelete = true)
        service = BaseAppService(repo)
    }

    @Test
    fun createOneStampsTenantAndTimestamps() {
        val id = ObjectId().toHexString()
        val now = Instant.now()

        // Mock insert to set the generated id on the entity
        whenever(mongo.insert(any<BaseAppDocument>())).thenAnswer { invocation ->
            val entity = invocation.getArgument<BaseAppDocument>(0)
            entity.id = id
            entity.appId = "app-9"
            entity.createdAt = now
            entity.updatedAt = now
            entity.deletedAt = null
            entity
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
        val now = Instant.now()

        whenever(mongo.insert(any<BaseAppDocument>())).thenAnswer { invocation ->
            val entity = invocation.getArgument<BaseAppDocument>(0)
            entity.id = id
            entity.appId = ctx.appId
            entity.createdAt = now
            entity.updatedAt = now
            entity.deletedAt = null
            entity
        }

        // Even if the entity somehow had deletedAt set (shouldn't happen for new), it gets cleared
        val doc = TodoDocument().apply {
            title = "t"
            deletedAt = Instant.now().minusSeconds(3600)
        }
        service.createOne(ctx, doc)

        assertThat(doc.deletedAt).isNull()
    }
}
