package com.ifmix.api.core.modules.demo.repo

import com.ifmix.api.core.common.db.RepoCtx
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

    fun insertOne(ctx: RepoCtx, appId: ObjectId, doc: TodoItemEntity): ObjectId {
        doc.appId = appId
        doc.createdAt = Instant.now()
        doc.updatedAt = Instant.now()
        mongo.insert(doc)
        return doc.id
    }

    fun findById(ctx: RepoCtx, appId: ObjectId, id: ObjectId): TodoItemEntity? {
        val query = Query(
            Criteria().andOperator(
                TodoItemEntity::id isEqualTo id,
                TodoItemEntity::appId isEqualTo appId,
                TodoItemEntity::deletedAt isEqualTo null,
            )
        )
        return mongo.findOne(query, TodoItemEntity::class.java)
    }

    fun findByIds(ctx: RepoCtx, appId: ObjectId, ids: List<ObjectId>): List<TodoItemEntity> {
        if (ids.isEmpty()) return emptyList()
        val query = Query(
            Criteria().andOperator(
                TodoItemEntity::appId isEqualTo appId,
                TodoItemEntity::id inValues ids,
                TodoItemEntity::deletedAt isEqualTo null,
            )
        )
        return mongo.find(query, TodoItemEntity::class.java)
    }

    fun findByTodoIds(ctx: RepoCtx, appId: ObjectId, todoIds: List<ObjectId>): List<TodoItemEntity> {
        if (todoIds.isEmpty()) return emptyList()
        val query = Query(
            Criteria().andOperator(
                TodoItemEntity::appId isEqualTo appId,
                TodoItemEntity::todoId inValues todoIds,
                TodoItemEntity::deletedAt isEqualTo null,
            )
        )
        return mongo.find(query, TodoItemEntity::class.java)
    }

    fun updateById(ctx: RepoCtx, appId: ObjectId, id: ObjectId, update: Update): Boolean {
        val query = Query(
            Criteria().andOperator(
                TodoItemEntity::id isEqualTo id,
                TodoItemEntity::appId isEqualTo appId,
                TodoItemEntity::deletedAt isEqualTo null,
            )
        )
        return mongo.updateFirst(query, update, TodoItemEntity::class.java).modifiedCount > 0
    }

    fun softDeleteById(ctx: RepoCtx, appId: ObjectId, id: ObjectId): Boolean {
        val query = Query(
            Criteria().andOperator(
                TodoItemEntity::id isEqualTo id,
                TodoItemEntity::appId isEqualTo appId,
                TodoItemEntity::deletedAt isEqualTo null,
            )
        )
        val update = Update().set(TodoItemEntity::deletedAt, Instant.now())
        return mongo.updateFirst(query, update, TodoItemEntity::class.java).modifiedCount > 0
    }

    fun softDeleteByIds(ctx: RepoCtx, appId: ObjectId, ids: List<ObjectId>): Int {
        if (ids.isEmpty()) return 0
        val query = Query(
            Criteria().andOperator(
                TodoItemEntity::id inValues ids,
                TodoItemEntity::appId isEqualTo appId,
                TodoItemEntity::deletedAt isEqualTo null,
            )
        )
        val update = Update().set(TodoItemEntity::deletedAt, Instant.now())
        return mongo.updateMulti(query, update, TodoItemEntity::class.java).modifiedCount.toInt()
    }

    fun softDeleteByTodoId(ctx: RepoCtx, appId: ObjectId, todoId: ObjectId): Int {
        val query = Query(
            Criteria().andOperator(
                TodoItemEntity::todoId isEqualTo todoId,
                TodoItemEntity::appId isEqualTo appId,
                TodoItemEntity::deletedAt isEqualTo null,
            )
        )
        val update = Update().set(TodoItemEntity::deletedAt, Instant.now())
        return mongo.updateMulti(query, update, TodoItemEntity::class.java).modifiedCount.toInt()
    }
}
