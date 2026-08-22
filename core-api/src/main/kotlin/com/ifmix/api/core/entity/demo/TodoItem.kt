package com.ifmix.api.core.entity.demo

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.MutableProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

@Entity
@Table(name = "demo_todo_item")
interface TodoItem : AppScopedProps, MutableProps {
    @Id
    val id: UUID

    val todoId: UUID
    val content: String
    val done: Boolean
    val note: String?
}
