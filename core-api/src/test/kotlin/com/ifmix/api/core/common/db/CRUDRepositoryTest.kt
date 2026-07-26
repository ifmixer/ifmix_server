package com.ifmix.api.core.common.db

import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.TodoDocument
import com.mongodb.client.result.UpdateResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bson.types.ObjectId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class CRUDRepositoryTest {

    @Mock
    private lateinit var mongo: MongoTemplate

    private val ctx = RequestContext(appId = "app-1")
    private lateinit var repo: CRUDRepository<TodoDocument>

    @BeforeEach
    fun init() {
        repo = object : CRUDRepository<TodoDocument>(mongo, TodoDocument::class.java, softDelete = true) {
            override fun extraCriteria(ctx: RequestContext): Criteria =
                Criteria.where("appId").`is`(ctx.appId)
        }
    }

    private fun makeDoc(title: String, idHex: String): TodoDocument =
        TodoDocument().apply {
            this.title = title
            appId = "app-1"
            id = idHex
            val now = Instant.now()
            createdAt = now
            updatedAt = now
        }

    @Test
    fun insertThenGetById() {
        val doc = makeDoc("hello", "507f1f77bcf86cd799439011")
        whenever(mongo.findOne(any<Query>(), eq(TodoDocument::class.java))).thenReturn(doc)
        assertThat(repo.getById(ctx, "507f1f77bcf86cd799439011").title).isEqualTo("hello")
    }

    @Test
    fun getByIdMissingThrowsNotFound() {
        whenever(mongo.findOne(any<Query>(), eq(TodoDocument::class.java))).thenReturn(null)
        assertThatThrownBy { repo.getById(ctx, ObjectId().toHexString()) }.isInstanceOf(ApiError::class.java)
    }

    @Test
    fun findByIdMissingReturnsNull() {
        whenever(mongo.findOne(any<Query>(), eq(TodoDocument::class.java))).thenReturn(null)
        assertThat(repo.findById(ctx, ObjectId().toHexString())).isNull()
    }

    @Test
    fun invalidIdHandledGracefully() {
        assertThat(repo.findById(ctx, "not-an-objectid")).isNull()
        assertThat(repo.updateById(ctx, "not-an-objectid", mapOf("title" to "x"))).isFalse()
        assertThat(repo.deleteById(ctx, "not-an-objectid")).isFalse()
    }

    @Test
    fun updateByIdAutoGeneratesSetFromMap() {
        whenever(mongo.updateFirst(any<Query>(), any<Update>(), eq(TodoDocument::class.java)))
            .thenReturn(UpdateResult.acknowledged(1L, 1L, null))
        val updated = makeDoc("new", "507f1f77bcf86cd799439011")
        whenever(mongo.findOne(any<Query>(), eq(TodoDocument::class.java))).thenReturn(updated)

        assertThat(repo.updateById(ctx, "507f1f77bcf86cd799439011", mapOf("title" to "new"))).isTrue()
        assertThat(repo.getById(ctx, "507f1f77bcf86cd799439011").title).isEqualTo("new")
    }

    @Test
    fun updateByIdMissingReturnsFalse() {
        whenever(mongo.updateFirst(any<Query>(), any<Update>(), eq(TodoDocument::class.java)))
            .thenReturn(UpdateResult.acknowledged(0L, 0L, null))
        assertThat(repo.updateById(ctx, ObjectId().toHexString(), mapOf("title" to "x"))).isFalse()
    }

    @Test
    fun softDeleteHidesFromFindAndList() {
        whenever(mongo.updateFirst(any<Query>(), any<Update>(), eq(TodoDocument::class.java)))
            .thenReturn(UpdateResult.acknowledged(1L, 1L, null))
        whenever(mongo.findOne(any<Query>(), eq(TodoDocument::class.java))).thenReturn(null)

        assertThat(repo.deleteById(ctx, "507f1f77bcf86cd799439011")).isTrue()
        assertThat(repo.findById(ctx, "507f1f77bcf86cd799439011")).isNull()
        assertThat(repo.findByCursor(ctx, CursorQueryInput(limit = 10)).items).isEmpty()
    }

    @Test
    fun findByCursorPaginatesDescendingById() {
        val doc1 = makeDoc("a", "507f1f77bcf86cd799439001")
        val doc2 = makeDoc("b", "507f1f77bcf86cd799439002")
        val doc3 = makeDoc("c", "507f1f77bcf86cd799439003")

        whenever(mongo.find(any<Query>(), eq(TodoDocument::class.java))).thenReturn(listOf(doc3, doc2, doc1))

        val p1 = repo.findByCursor(ctx, CursorQueryInput(order = CursorQueryInput.Order.DESC, limit = 2))
        assertThat(p1.items.map { it.id!! }).containsExactly("507f1f77bcf86cd799439003", "507f1f77bcf86cd799439002")
        assertThat(p1.hasMore).isTrue()
        assertThat(p1.nextCursor).isEqualTo("507f1f77bcf86cd799439002")

        whenever(mongo.find(any<Query>(), eq(TodoDocument::class.java))).thenReturn(listOf(doc1))

        val p2 = repo.findByCursor(ctx, CursorQueryInput(cursor = p1.nextCursor, order = CursorQueryInput.Order.DESC, limit = 2))
        assertThat(p2.items.map { it.id!! }).containsExactly("507f1f77bcf86cd799439001")
        assertThat(p2.hasMore).isFalse()
        assertThat(p2.nextCursor).isNull()
    }
}
