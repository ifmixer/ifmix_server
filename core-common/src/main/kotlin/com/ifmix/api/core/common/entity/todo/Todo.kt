package com.ifmix.api.core.common.entity.todo

import com.ifmix.api.core.common.entity.AppScopedProps
import com.ifmix.api.core.common.entity.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

@Entity
@Table(name = "core_todo")
interface Todo : AppScopedProps, SoftDeletableProps {

    @Id
    val id: UUID

    override val appId: UUID

    val installId: UUID?

    val userId: UUID?

    val title: String

    val done: Boolean

    /** JSONB 元数据，用于测试 Jimmer 对嵌套 JSON 局部更新的行为 */
    @Serialized
    val meta: Map<String, Any?>?

    @OneToMany(mappedBy = "todo")
    val items: List<TodoItem>
}
