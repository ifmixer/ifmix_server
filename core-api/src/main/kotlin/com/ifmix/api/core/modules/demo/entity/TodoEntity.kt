package com.ifmix.api.core.modules.demo.entity

import com.ifmix.api.core.common.db.BaseAppEntity
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import org.bson.types.ObjectId

/**
 * todos 集合。items 拆为独立集合 todo_items，通过 todoId 关联。
 * CompoundIndex(appId + userId + installId) 支持按归属查询。
 */
@Document(collection = "todos")
@CompoundIndex(name = "todos_app_idx", def = "{'appId': 1, '_id': 1}")
class TodoEntity : BaseAppEntity() {
    lateinit var title: String
    var done: Boolean = false
    /** JSONB 元数据 */
    var meta: Map<String, Any?>? = null
    /** 拥有者 userId（登录用户），可为 null（匿名用户时不填）。 */
    var userId: ObjectId? = null
    /** 拥有者 installId（匿名或登录均填）。 */
    var installId: ObjectId? = null
}
