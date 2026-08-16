package com.ifmix.api.core.modules.todo.repo

import com.ifmix.api.core.common.db.BaseAppDocument
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.document.TodoItemDocument
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

    fun insertOne(ctx: RequestContext, doc: TodoItemDocument): String {
        doc.appId = ObjectId(ctx.appId)
        mongo.insert(doc)
        return doc.id.toHexString()
    }

    fun findById(ctx: RequestContext, id: String): TodoItemDocument? {
        val query = Query(
            Criteria().andOperator(
                TodoItemDocument::id isEqualTo id,
                TodoItemDocument::appId isEqualTo ctx.appId,
                BaseAppDocument::deletedAt isEqualTo null,
            )
        )
        return mongo.findOne(query, TodoItemDocument::class.java)
    }

    fun findByTodoIds(ctx: RequestContext, todoIds: List<String>): List<TodoItemDocument> {
        if (todoIds.isEmpty()) return emptyList()
        val query = Query(
            Criteria().andOperator(
                TodoItemDocument::appId isEqualTo ctx.appId,
                TodoItemDocument::todoId inValues todoIds,
                BaseAppDocument::deletedAt isEqualTo null,
            )
        )
        return mongo.find(query, TodoItemDocument::class.java)
    }

    /** 按 id 部分更新。 */
    fun updateById(ctx: RequestContext, id: String, update: org.springframework.data.mongodb.core.query.Update): Boolean {
        val query = Query(
            Criteria().andOperator(
                TodoItemDocument::id isEqualTo id,
                TodoItemDocument::appId isEqualTo ctx.appId,
                BaseAppDocument::deletedAt isEqualTo null,
            )
        )
        return mongo.updateFirst(query, update, TodoItemDocument::class.java).modifiedCount > 0
    }

    fun softDeleteById(ctx: RequestContext, id: String): Boolean {
        val query = Query(
            Criteria().andOperator(
                TodoItemDocument::id isEqualTo id,
                TodoItemDocument::appId isEqualTo ctx.appId,
                BaseAppDocument::deletedAt isEqualTo null,
            )
        )
        val update = Update().set(BaseAppDocument::deletedAt, Instant.now())
        return mongo.updateFirst(query, update, TodoItemDocument::class.java).modifiedCount > 0
    }

    fun softDeleteByIds(ctx: RequestContext, ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val query = Query(
            Criteria().andOperator(
                TodoItemDocument::id inValues ids,
                TodoItemDocument::appId isEqualTo ctx.appId,
                BaseAppDocument::deletedAt isEqualTo null,
            )
        )
        val update = Update().set(BaseAppDocument::deletedAt, Instant.now())
        return mongo.updateMulti(query, update, TodoItemDocument::class.java).modifiedCount.toInt()
    }

    fun softDeleteByTodoId(ctx: RequestContext, todoId: String): Int {
        val query = Query(
            Criteria().andOperator(
                TodoItemDocument::todoId isEqualTo todoId,
                TodoItemDocument::appId isEqualTo ctx.appId,
                BaseAppDocument::deletedAt isEqualTo null,
            )
        )
        val update = Update().set(BaseAppDocument::deletedAt, Instant.now())
        return mongo.updateMulti(query, update, TodoItemDocument::class.java).modifiedCount.toInt()
    }
}
