package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.jimmer.dto.todo.TodoCreateInput
import com.ifmix.api.core.common.jimmer.dto.todo.TodoView
import com.ifmix.api.core.common.jimmer.filter.RequestContextHolder
import com.ifmix.api.core.common.jimmer.repository.todo.TodoRepository
import com.ifmix.api.core.support.AbstractJimmerTest
import assertk.assertThat
import assertk.assertions.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class TodoServiceJimmerTest : AbstractJimmerTest() {

    private lateinit var service: TodoService

    private val ctx = RequestContext(appId = "00000000-0000-0000-0000-000000000001")

    @BeforeEach
    fun setUp() {
        val registry = createTestRegistry()
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        val repo = TodoRepository(registry)
        service = TodoService(repo)
        RequestContextHolder.set(ctx)
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.clear()
    }

    @Test
    fun `create and get round-trip`() {
        val input = TodoCreateInput(title = "buy milk", done = false)
        val created = service.create(ctx, input)
        assertThat(created.id).isNotNull()

        val found = service.getById(ctx, created.id, TodoView::class)
        assertThat(found.title).isEqualTo("buy milk")
        assertThat(found.done).isFalse()
    }

    @Test
    fun `delete soft-deletes and hides from queries`() {
        val input = TodoCreateInput(title = "to delete", done = false)
        val created = service.create(ctx, input)
        service.deleteById(ctx, created.id)
        val found = service.findById(ctx, created.id)
        assertThat(found).isNull()
    }

    @Test
    fun `findByCursor returns paginated results`() {
        repeat(5) { i ->
            service.create(ctx, TodoCreateInput(title = "todo-$i", done = false))
        }
        val page = service.findByCursor(ctx, CursorQueryInput(limit = 3))
        assertThat(page.items).hasSize(3)
        assertThat(page.hasMore).isTrue()
    }
}
