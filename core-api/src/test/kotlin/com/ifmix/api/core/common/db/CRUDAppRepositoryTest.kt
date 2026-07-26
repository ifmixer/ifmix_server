package com.ifmix.api.core.common.db

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.TodoDocument
import com.ifmix.api.core.support.AbstractMongoTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class CRUDAppRepositoryTest : AbstractMongoTest() {

    private val app1 = RequestContext(appId = "app-1")
    private val app2 = RequestContext(appId = "app-2")
    private lateinit var repo: CRUDAppRepository<TodoDocument>

    @BeforeEach
    fun init() {
        repo = CRUDAppRepository(mongoTemplate, TodoDocument::class.java, softDelete = true)
    }

    private fun insert(ctx: RequestContext, title: String): String {
        val d = TodoDocument().apply {
            this.title = title
            appId = ctx.appId
            val now = Instant.now()
            createdAt = now
            updatedAt = now
        }
        repo.insertOne(ctx, d)
        return d.id!!
    }

    @Test
    fun findByIdIsolatedByTenant() {
        val id = insert(app1, "secret")
        assertThat(repo.findById(app2, id)).isNull()
        assertThat(repo.getById(app1, id).title).isEqualTo("secret")
    }

    @Test
    fun findByCursorIsolatedByTenant() {
        insert(app1, "a1")
        insert(app2, "b1")
        insert(app2, "b2")
        val page = repo.findByCursor(app2, input = CursorQueryInput(limit = 10))
        assertThat(page.items.map { it.title }).containsExactlyInAnyOrder("b1", "b2")
    }

    @Test
    fun crossTenantUpdateAndDeleteAreNoop() {
        val id = insert(app1, "x")
        assertThat(repo.updateById(app2, id, mapOf("title" to "hacked"))).isFalse()
        assertThat(repo.deleteById(app2, id)).isFalse()
        assertThat(repo.getById(app1, id).title).isEqualTo("x")
    }
}
