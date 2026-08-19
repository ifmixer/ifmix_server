package com.ifmix.api.core.entity.demo

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

/**
 * Todo 领域模型 (Jimmer entity)。
 */
@Entity
@Table(name = "core_todo")
interface Todo : AppScopedProps, SoftDeletableProps {
    @Id
    val id: UUID


    val installId: UUID?
    val userId: UUID?
    val title: String
    val done: Boolean
}
