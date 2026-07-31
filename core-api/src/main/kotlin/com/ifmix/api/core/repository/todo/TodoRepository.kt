package com.ifmix.api.core.repository.todo

import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import com.ifmix.api.core.entity.todo.Todo
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Repository

@Repository
class TodoRepository(sql: KSqlClient,) : BaseAppCrudRepository<Todo>(sql, Todo::class)
