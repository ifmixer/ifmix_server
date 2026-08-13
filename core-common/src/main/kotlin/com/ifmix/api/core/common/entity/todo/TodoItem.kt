package com.ifmix.api.core.common.entity.todo

import com.ifmix.api.core.common.entity.AppScopedProps
import com.ifmix.api.core.common.entity.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

@Entity
@Table(name = "core_todo_item")
interface TodoItem : AppScopedProps, SoftDeletableProps {

    @Id
    val id: UUID

    override val appId: UUID

    @ManyToOne
    val todo: Todo

    val content: String

    val done: Boolean
}
