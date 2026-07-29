package com.ifmix.api.core.entity.todo

import com.ifmix.api.core.entity.AppScopedProps
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "todo")
interface Todo : AppScopedProps {

    @Id
    val id: UUID

    override val appId: UUID

    val title: String

    val done: Boolean

    @LogicalDeleted("now")
    val deletedAt: Instant?

    val createdAt: Instant

    val updatedAt: Instant

    @OneToMany(mappedBy = "todo")
    val items: List<TodoItem>
}
