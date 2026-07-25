package com.ifmix.api.core.common.db

import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.TodoDocument
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bson.types.ObjectId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import com.mongodb.client.result.UpdateResult
import java.time.Instant

import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class BaseRepositoryTest {

    @Mock
    private lateinit var mongo: MongoTemplate

    private val ctx = RequestContext(appId = "app-1")
    private lateinit var repo: BaseRepository<TodoDocument>

    @BeforeEach
    fun init() {
        repo = object : BaseRepository<TodoDocument>(mongo, TodoDocument::class.java, softDelete = true) {
            override fun extraCriteria(ctx: RequestContext): org.springframework.data.mongodb.core.query.Criteria? {
                return org.springframework.data.mongodb.core.query.Criteria.where("appId").`is`(ctx.appId)
            }
        }
    }

    private fun makeDoc(title: String, idHex: String): TodoDocument {
        val d = TodoDocument().apply {
            this.title = title
            appId = "app-1"
            this.id = idHex
            val now = Instant.now()
            createdAt = now
            updatedAt = now
        }
        return d
    }

    @Test
    fun insertThenGetById() {
        val doc = makeDoc("hello", "507f1f77bcf86cd799439011")
        whenever(mongo.findOne(any<Query>(), eq(TodoDocument::class.java))).thenReturn(doc)
        val found = repo.getById(ctx, "507f1f77bcf86cd799439011")
        assertThat(found.title).isEqualTo("hello")
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
    fun updateByIdModifiesFields() {
        val result = UpdateResult.acknowledged(1L, 1L, null)
        whenever(mongo.updateFirst(any<Query>(), any<Update>(), eq(TodoDocument::class.java))).thenReturn(result)

        val updatedDoc = makeDoc("new", "507f1f77bcf86cd799439011")
        whenever(mongo.findOne(any<Query>(), eq(TodoDocument::class.java))).thenReturn(updatedDoc)

        assertThat(repo.updateById(ctx, "507f1f77bcf86cd799439011", mapOf("title" to "new"))).isTrue()
        assertThat(repo.getById(ctx, "507f1f77bcf86cd799439011").title).isEqualTo("new")
    }

    @Test
    fun updateByIdMissingReturnsFalse() {
        val result = UpdateResult.acknowledged(0L, 0L, null)
        whenever(mongo.updateFirst(any<Query>(), any<Update>(), eq(TodoDocument::class.java))).thenReturn(result)

        assertThat(repo.updateById(ctx, ObjectId().toHexString(), mapOf("title" to "x"))).isFalse()
    }

    @Test
    fun softDeleteHidesFromFindAndList() {
        // Soft delete update succeeds
        val delResult = UpdateResult.acknowledged(1L, 1L, null)
        whenever(mongo.updateFirst(any<Query>(), any<Update>(), eq(TodoDocument::class.java))).thenReturn(delResult)

        // After soft delete, find returns null (deletedAt is set, so extra criteria filters it out)
        whenever(mongo.findOne(any<Query>(), eq(TodoDocument::class.java))).thenReturn(null)

        assertThat(repo.deleteById(ctx, "507f1f77bcf86cd799439011")).isTrue()
        assertThat(repo.findById(ctx, "507f1f77bcf86cd799439011")).isNull()
        assertThat(repo.findMany(ctx, CursorQuery(limit = 10)).items).isEmpty()
    }

    @Test
    fun findManyPaginatesDescendingById() {
        val doc1 = makeDoc("a", "507f1f77bcf86cd799439001")
        val doc2 = makeDoc("b", "507f1f77bcf86cd799439002")
        val doc3 = makeDoc("c", "507f1f77bcf86cd799439003")

        // First page: limit=3 returns 3 docs (hasMore=true since we asked for limit+1=3 with limit=2)
        whenever(mongo.find(any<Query>(), eq(TodoDocument::class.java))).thenReturn(listOf(doc3, doc2, doc1))

        val p1 = repo.findMany(ctx, CursorQuery(order = CursorQuery.Order.DESC, limit = 2))
        assertThat(p1.items.map { it.id!! }).containsExactly("507f1f77bcf86cd799439003", "507f1f77bcf86cd799439002")
        assertThat(p1.hasMore).isTrue()
        assertThat(p1.nextCursor).isEqualTo("507f1f77bcf86cd799439002")

        // Second page: only 1 more doc (hasMore=false)
        whenever(mongo.find(any<Query>(), eq(TodoDocument::class.java))).thenReturn(listOf(doc1))

        val p2 = repo.findMany(ctx, CursorQuery(cursor = p1.nextCursor, order = CursorQuery.Order.DESC, limit = 2))
        assertThat(p2.items.map { it.id!! }).containsExactly("507f1f77bcf86cd799439001")
        assertThat(p2.hasMore).isFalse()
        assertThat(p2.nextCursor).isNull()
    }
}
