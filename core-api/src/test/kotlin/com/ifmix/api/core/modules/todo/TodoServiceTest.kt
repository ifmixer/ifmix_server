package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.modules.todo.CreateTodoRequest
import com.ifmix.api.core.modules.todo.UpdateTodoRequest
import com.ifmix.api.core.common.db.CRUDRepository
import com.ifmix.api.core.common.service.CRUDService
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.support.AbstractMongoTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TodoServiceTest : AbstractMongoTest() {

    private val ctx = RequestContext(appId = "app-7")
    private lateinit var service: TodoService

    @BeforeEach
    fun init() {
        service = TodoService(
            CRUDService(CRUDRepository(mongoTemplate, TodoDocument::class.java)),
        )
    }

    @Test
    fun createEmbedsItemsWithGeneratedIds() {
        val id = service.create(
            ctx,
            CreateTodoRequest("shopping", listOf(CreateTodoItem("milk"), CreateTodoItem("eggs"))),
        )

        val doc = service.getById(ctx, id)
        assertThat(doc.title).isEqualTo("shopping")
        assertThat(doc.done).isFalse()
        assertThat(doc.appId).isEqualTo("app-7")
        assertThat(doc.items).hasSize(2)
        assertThat(doc.items).allSatisfy { assertThat(it.id).isNotBlank() }
        assertThat(doc.items.map { it.content }).containsExactly("milk", "eggs")
    }

    @Test
    fun createWithNullItemsGivesEmptyList() {
        val id = service.create(ctx, CreateTodoRequest("t", null))
        assertThat(service.getById(ctx, id).items).isEmpty()
    }

    @Test
    fun updatePartialSetsOnlyProvidedFields() {
        val id = service.create(ctx, CreateTodoRequest("keep-title", null))

        assertThat(service.update(ctx, id, UpdateTodoRequest(done = true))).isTrue()

        val doc = service.getById(ctx, id)
        assertThat(doc.done).isTrue()
        assertThat(doc.title).isEqualTo("keep-title")
    }

    @Test
    fun deleteSoftDeletes() {
        val id = service.create(ctx, CreateTodoRequest("t", null))
        assertThat(service.deleteById(ctx, id)).isTrue()
        assertThat(service.findById(ctx, id)).isNull()
    }
}
