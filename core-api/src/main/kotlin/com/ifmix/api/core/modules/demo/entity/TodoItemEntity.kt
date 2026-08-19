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
