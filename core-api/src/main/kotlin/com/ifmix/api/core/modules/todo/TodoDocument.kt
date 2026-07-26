package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.db.CRUDAppDocument
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document

/** todos 集合。内嵌 items（聚合边界内、有界，单文档原子读写）。 */
@Document(collection = "todos")
@CompoundIndex(name = "todos_app_id_id_idx", def = "{'appId': 1, '_id': 1}")
class TodoDocument : CRUDAppDocument() {
    var title: String? = null
    var done: Boolean = false
    var items: MutableList<TodoItem> = mutableListOf()
}
