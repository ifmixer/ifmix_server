package com.ifmix.api.core.common.jimmer.repository.todo

import com.ifmix.api.core.common.jimmer.base.BaseAppCrudRepository
import com.ifmix.api.core.common.jimmer.entity.todo.Todo
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component

@Component
class TodoRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<Todo>(sql, Todo::class)
