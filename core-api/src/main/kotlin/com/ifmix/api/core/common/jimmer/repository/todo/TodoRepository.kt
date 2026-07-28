package com.ifmix.api.core.common.jimmer.repository.todo

import com.ifmix.api.core.common.jimmer.base.BaseAppCrudRepository
import com.ifmix.api.core.common.jimmer.cluster.ClusterRegistry
import com.ifmix.api.core.common.jimmer.entity.todo.Todo
import org.springframework.stereotype.Component

@Component
class TodoRepository(
    clusterRegistry: ClusterRegistry,
) : BaseAppCrudRepository<Todo>(clusterRegistry, Todo::class)
