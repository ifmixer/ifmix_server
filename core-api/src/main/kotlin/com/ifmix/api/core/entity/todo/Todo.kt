package com.ifmix.api.core.entity.todo

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

@Entity
@Table(name = "core_todo")
interface Todo : AppScopedProps, SoftDeletableProps {

    @Id
    val id: UUID

    override val appId: UUID

    val title: String

    val done: Boolean

    @OneToMany(mappedBy = "todo")
    val items: List<TodoItem>
}
