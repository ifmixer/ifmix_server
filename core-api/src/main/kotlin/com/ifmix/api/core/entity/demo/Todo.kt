package com.ifmix.api.core.entity.demo

import com.ifmix.api.core.entity.common.BaseAppEntity
import com.ifmix.api.core.entity.common.CustomerOwnedProps
import com.ifmix.api.core.entity.common.SoftDeletableProps
import org.babyfish.jimmer.sql.*

/**
 * Todo 领域模型 (Jimmer entity)。
 */
@Entity
@Table(name = "demo_todo")
interface Todo : BaseAppEntity, SoftDeletableProps, CustomerOwnedProps {

    val title: String
    val done: Boolean
    val note: String?

    @Serialized
    val meta: Map<String, Any?>?

    @Serialized
    val recommend: TodoRecommend?
}
