package com.ifmix.api.core.modules.todo.service

import com.ifmix.api.core.generated.types.UpdateTodoItemsMutationInput
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.entity.todo.TodoItem
import com.ifmix.api.core.modules.todo.repo.TodoItemRepository
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class TodoItemInternalService(
    private val repo: TodoItemRepository,
) {
    fun update(sc: SvcCtx, input: UpdateTodoItemsMutationInput) {
        val appId = sc.op.appId!!
        val now = Instant.now()
        input.create?.takeIf { it.isNotEmpty() }?.let { creates ->
            repo.batchInsert(sc, creates.map { c ->
                TodoItem(id = com.ifmix.api.core.infra.db.UuidV7.generate(), appId = appId, todoId = c.todoId,
                    content = c.content, done = c.done ?: false, createdAt = now)
            })
        }
        input.update?.takeIf { it.isNotEmpty() }?.let { updates ->
            repo.batchUpdate(sc, appId, updates)
        }
        input.delete?.takeIf { it.isNotEmpty() }?.let { ids ->
            repo.deleteByIds(sc, appId, ids)
        }
    }
}
