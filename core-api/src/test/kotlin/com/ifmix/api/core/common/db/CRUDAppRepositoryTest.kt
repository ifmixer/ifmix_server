package com.ifmix.api.core.infra.db

import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.infra.db.TestDocument
import com.ifmix.api.core.support.AbstractMongoTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/** Tenant isolation via AppScoped capability (auto-detected from TodoDocument). */
class CRUDAppRepositoryTest : AbstractMongoTest() {

    private val app1 = RequestContext(appId = "app-1")
    private val app2 = RequestContext(appId = "app-2")
    private lateinit var repo: CRUDRepository<TodoDocument>

    @BeforeEach
    fun init() {
        // TodoDocument extends BaseAppDocument → auto-detects AppScoped + SoftDeletable
        repo = CRUDRepository(mongoTemplate, TodoDocument::class.java)
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
