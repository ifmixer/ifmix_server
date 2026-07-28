package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.jimmer.base.BaseAppCrudService
import com.ifmix.api.core.common.jimmer.entity.todo.Todo
import com.ifmix.api.core.common.jimmer.repository.todo.TodoRepository
import org.springframework.stereotype.Service

/**
 * Todo 业务逻辑。标准 CRUD 由 BaseAppCrudService 提供。
 * 领域特有方法在这里追加。
 */
@Service
class TodoService(
    todoRepo: TodoRepository,
) : BaseAppCrudService<Todo>(todoRepo)
