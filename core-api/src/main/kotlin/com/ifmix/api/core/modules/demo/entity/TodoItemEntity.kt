package com.ifmix.api.core.modules.demo.entity

import com.ifmix.api.core.common.db.BaseAppEntity
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import org.bson.types.ObjectId

/**
 * todo_items 集合文档。每个 item 独立存储，通过 [todoId] 关联到父 Todo。
 * CompoundIndex(appId + todoId) 支持 DataLoader 按 todoIds 批量查询。
 */
@Document(collection = "todo_items")
@CompoundIndex(name = "todo_items_app_todo_idx", def = "{'appId': 1, 'todoId': 1}")
class TodoItemEntity : BaseAppEntity() {
    lateinit var todoId: ObjectId
    lateinit var content: String
    var done: Boolean = false
}

/** TodoItemEntity → GraphQL TodoItem 转换（Entity 直出，零 mapper）。 */
fun TodoItemEntity.toTodoItem(): com.ifmix.api.core.graphql.generated.types.TodoItem =
    com.ifmix.api.core.graphql.generated.types.TodoItem(
        id = this.id.toHexString(),
        todoId = this.todoId.toHexString(),
        content = this.content,
        done = this.done,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt,
    )
