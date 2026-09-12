package com.ifmix.core.api.entity.demo

import com.ifmix.core.api.entity.common.BaseProjectEntity
import com.ifmix.core.api.entity.common.CustomerIdProps
import com.ifmix.core.api.entity.common.SoftDeletableProps
import org.babyfish.jimmer.sql.*

/**
 * Todo 领域模型 (Jimmer entity)。
 */
@Entity
@Table(name = "demo_todo")
interface Todo : BaseProjectEntity, SoftDeletableProps, CustomerIdProps {

    val title: String
    val done: Boolean
    val note: String?

    @Serialized
    val meta: Map<String, Any?>?

    @Serialized
    val recommend: TodoRecommend?
}
