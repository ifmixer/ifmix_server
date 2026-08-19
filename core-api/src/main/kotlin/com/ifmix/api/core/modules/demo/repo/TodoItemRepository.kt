package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.common.http.RepoCtx
import com.ifmix.api.core.modules.demo.entity.TodoItemEntity
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.isEqualTo
import org.springframework.data.mongodb.core.query.inValues
import java.time.Instant

/**
 * TodoItem 仓储。直接持有 MongoTemplate，提供批量按 todoId 查询（DataLoader 专用）和软删操作。
 */
class TodoItemRepository(private val mongo: MongoTemplate) {

    fun insertOne(ctx: RepoCtx, appId: String, doc: TodoItemEntity): String {
        doc.appId = ObjectId(appId)
        doc.createdAt = Instant.now()
        doc.updatedAt = Instant.now()
        mongo.insert(doc)
        return doc.id.toHexString()
    }

    fun findById(ctx: RepoCtx, appId: String, id: String): TodoItemEntity? {
        val query = Query(
            Criteria().andOperator(
                TodoItemEntity::id isEqualTo ObjectId(id),
                TodoItemEntity::appId isEqualTo ObjectId(appId),
                TodoItemEntity::deletedAt isEqualTo null,
            )
        )
        return mongo.findOne(query, TodoItemEntity::class.java)
    }

    fun findByIds(ctx: RepoCtx, appId: String, ids: List<String>): List<TodoItemEntity> {
        if (ids.isEmpty()) return emptyList()
        val query = Query(
            Criteria().andOperator(
                TodoItemEntity::appId isEqualTo ObjectId(appId),
                TodoItemEntity::id inValues ids.map { ObjectId(it) },
                TodoItemEntity::deletedAt isEqualTo null,
            )
        )
        return mongo.find(query, TodoItemEntity::class.java)
    }

    fun findByTodoIds(ctx: RepoCtx, appId: String, todoIds: List<String>): List<TodoItemEntity> {
        if (todoIds.isEmpty()) return emptyList()
        val query = Query(
            Criteria().andOperator(
                TodoItemEntity::appId isEqualTo ObjectId(appId),
                TodoItemEntity::todoId inValues todoIds.map { ObjectId(it) },
                TodoItemEntity::deletedAt isEqualTo null,
            )
        )
        return mongo.find(query, TodoItemEntity::class.java)
    }

    fun updateById(ctx: RepoCtx, appId: String, id: String, update: Update): Boolean {
        val query = Query(
            Criteria().andOperator(
                TodoItemEntity::id isEqualTo ObjectId(id),
                TodoItemEntity::appId isEqualTo ObjectId(appId),
                TodoItemEntity::deletedAt isEqualTo null,
            )
        )
        return mongo.updateFirst(query, update, TodoItemEntity::class.java).modifiedCount > 0
    }

    fun softDeleteById(ctx: RepoCtx, appId: String, id: String): Boolean {
        val query = Query(
            Criteria().andOperator(
                TodoItemEntity::id isEqualTo ObjectId(id),
                TodoItemEntity::appId isEqualTo ObjectId(appId),
                TodoItemEntity::deletedAt isEqualTo null,
            )
        )
        val update = Update().set(TodoItemEntity::deletedAt, Instant.now())
        return mongo.updateFirst(query, update, TodoItemEntity::class.java).modifiedCount > 0
    }

    fun softDeleteByIds(ctx: RepoCtx, appId: String, ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val query = Query(
            Criteria().andOperator(
                TodoItemEntity::id inValues ids.map { ObjectId(it) },
                TodoItemEntity::appId isEqualTo ObjectId(appId),
                TodoItemEntity::deletedAt isEqualTo null,
            )
        )
        val update = Update().set(TodoItemEntity::deletedAt, Instant.now())
        return mongo.updateMulti(query, update, TodoItemEntity::class.java).modifiedCount.toInt()
    }

    fun softDeleteByTodoId(ctx: RepoCtx, appId: String, todoId: String): Int {
        val query = Query(
            Criteria().andOperator(
                TodoItemEntity::todoId isEqualTo ObjectId(todoId),
                TodoItemEntity::appId isEqualTo ObjectId(appId),
                TodoItemEntity::deletedAt isEqualTo null,
            )
        )
        val update = Update().set(TodoItemEntity::deletedAt, Instant.now())
        return mongo.updateMulti(query, update, TodoItemEntity::class.java).modifiedCount.toInt()
    }
}
