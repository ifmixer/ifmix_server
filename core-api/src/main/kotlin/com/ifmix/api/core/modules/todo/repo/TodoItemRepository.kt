package com.ifmix.api.core.modules.todo.repo

import com.ifmix.api.core.common.db.CRUDRepository
import com.ifmix.api.core.common.db.MongoClusterResolver
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.document.TodoItemDocument
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query

/**
 * TodoItem 仓储：在 [CRUDRepository] 基础上提供按 todoIds 批量查询（DataLoader 使用）。
 */
class TodoItemRepository(clusterResolver: MongoClusterResolver) :
    CRUDRepository<TodoItemDocument>(clusterResolver.primary(), TodoItemDocument::class.java) {

    /**
     * 按 appId + todoIds 批量查询（软删自动过滤）。
     * DataLoader 将 todoIds 收集到一个集合里一次性查，避免 N+1。
     */
    fun findByTodoIds(ctx: RequestContext, todoIds: List<String>): List<TodoItemDocument> {
        if (todoIds.isEmpty()) return emptyList()
        val query = Query(
            Criteria.where("appId").`is`(ctx.appId)
                .and("todoId").`in`(todoIds)
                .and("deletedAt").`is`(null)
        )
        return mongo.find(query, TodoItemDocument::class.java)
    }
}
