package com.ifmix.api.core.modules.todo.repo

import com.ifmix.api.core.common.db.BaseAppEntity
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.entity.TodoItemEntity
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.isEqualTo
import org.springframework.data.mongodb.core.query.inValues
import java.time.Instant
import com.ifmix.api.core.modules.todo.repo.TodoItemRepository

/**
 * TodoItem 仓储。直接持有 MongoTemplate，提供批量按 todoId 查询（DataLoader 专用）和软删操作。
 */
class TodoItemRepository(private val mongo: MongoTemplate) {

    fun insertOne(ctx: RequestContext, doc: TodoItemEntity): String {
        doc.appId = ObjectId(ctx.appId)
        mongo.insert(doc)
        return doc.id.toHexString()
    }

    fun findById(ctx: RequestContext, id: String): TodoItemEntity? {
        val query = Query(
            Criteria().andOperator(
                TodoItemEntity::id isEqualTo id,
                TodoItemEntity::appId isEqualTo ctx.appId,
                BaseAppEntity::deletedAt isEqualTo null,
            )
        )
        return mongo.findOne(query, TodoItemEntity::class.java)
    }

    fun findByTodoIds(ctx: RequestContext, todoIds: List<String>): List<TodoItemEntity> {
        if (todoIds.isEmpty()) return emptyList()
        val query = Query(
            Criteria().andOperator(
                TodoItemEntity::appId isEqualTo ctx.appId,
                TodoItemEntity::todoId inValues todoIds,
                TodoItemEntity::deletedAt isEqualTo null,
            )
        )
        return mongo.find(query, TodoItemEntity::class.java)
    }

    /** 按 id 部分更新。 */
    fun updateById(ctx: RequestContext, id: String, update: org.springframework.data.mongodb.core.query.Update): Boolean {
        val query = Query(
            Criteria().andOperator(
                TodoItemEntity::id isEqualTo id,
                TodoItemEntity::appId isEqualTo ctx.appId,
                TodoItemEntity::deletedAt isEqualTo null,
            )
        )
        return mongo.updateFirst(query, update, TodoItemEntity::class.java).modifiedCount > 0
    }

    fun softDeleteById(ctx: RequestContext, id: String): Boolean {
        val query = Query(
            Criteria().andOperator(
                TodoItemEntity::id isEqualTo id,
                TodoItemEntity::appId isEqualTo ctx.appId,
                BaseAppEntity::deletedAt isEqualTo null,
            )
        )
        val update = Update().set(BaseAppEntity::deletedAt, Instant.now())
        return mongo.updateFirst(query, update, TodoItemEntity::class.java).modifiedCount > 0
    }

    fun softDeleteByIds(ctx: RequestContext, ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val query = Query(
            Criteria().andOperator(
                TodoItemEntity::id inValues ids,
                TodoItemEntity::appId isEqualTo ctx.appId,
                BaseAppEntity::deletedAt isEqualTo null,
            )
        )
        val update = Update().set(BaseAppEntity::deletedAt, Instant.now())
        return mongo.updateMulti(query, update, TodoItemEntity::class.java).modifiedCount.toInt()
    }

    fun softDeleteByTodoId(ctx: RequestContext, todoId: String): Int {
        val query = Query(
            Criteria().andOperator(
                TodoItemEntity::todoId isEqualTo todoId,
                TodoItemEntity::appId isEqualTo ctx.appId,
                BaseAppEntity::deletedAt isEqualTo null,
            )
        )
        val update = Update().set(BaseAppEntity::deletedAt, Instant.now())
        return mongo.updateMulti(query, update, TodoItemEntity::class.java).modifiedCount.toInt()
    }
}
