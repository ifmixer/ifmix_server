package com.ifmix.api.core.entity.demo

import com.ifmix.api.core.entity.BaseAppEntity
import com.ifmix.api.core.entity.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * Todo 领域模型 (Jimmer entity)。
 */
@Entity
@Table(name = "demo_todo")
interface Todo : BaseAppEntity, SoftDeletableProps {

    val installId: UUID?
    val userId: UUID?
    val title: String
    val done: Boolean
    val note: String?

    @Serialized
    val meta: Map<String, Any?>?

    @Serialized
    val recommend: TodoRecommend?
}
