package com.ifmix.api.core.common.jimmer.entity.todo

import com.ifmix.api.core.common.jimmer.entity.AppScopedProps
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "todo_item")
interface TodoItem : AppScopedProps {

    @Id
    val id: UUID

    override val appId: UUID

    @ManyToOne
    val todo: Todo

    val content: String

    val done: Boolean

    @LogicalDeleted("now")
    val deletedAt: Instant?

    val createdAt: Instant

    val updatedAt: Instant
}
