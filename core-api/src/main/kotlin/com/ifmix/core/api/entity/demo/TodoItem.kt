package com.ifmix.core.api.entity.demo

import com.ifmix.core.api.entity.common.BaseProjectEntity
import org.babyfish.jimmer.sql.*
import java.util.UUID

@Entity
@Table(name = "core_demo_todo_item")
interface TodoItem : BaseProjectEntity {

    val todoId: UUID
    val content: String
    val done: Boolean
    val note: String?
}
