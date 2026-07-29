package com.ifmix.api.core.service.todo

import com.ifmix.api.core.service.base.BaseAppCrudService
import com.ifmix.api.core.entity.todo.Todo
import com.ifmix.api.core.repository.todo.TodoRepository
import org.springframework.stereotype.Service

/**
 * Todo 业务逻辑。标准 CRUD 由 BaseAppCrudService 提供。
 * 领域特有方法在这里追加。
 */
@Service
class TodoService(
    todoRepo: TodoRepository,
) : BaseAppCrudService<Todo>(todoRepo)
