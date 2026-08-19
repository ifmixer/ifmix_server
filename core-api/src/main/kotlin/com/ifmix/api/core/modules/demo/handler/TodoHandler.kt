package com.ifmix.api.core.modules.demo.handler

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.entity.demo.Todo
import com.ifmix.api.core.entity.demo.TodoItem
import com.ifmix.api.core.modules.demo.repo.TodoItemRepository
import com.ifmix.api.core.modules.demo.repo.TodoRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class TodoHandler(
    private val todoRepo: TodoRepository,
    private val todoItemRepo: TodoItemRepository,
) {
    fun create(sc: SvcCtx, title: String, done: Boolean?): Todo {
        val appId = sc.appId!!
        val now = Instant.now()
        val id = UuidV7.generate()
        val todo = Todo {
            this.id = id
            this.appId = appId
            this.title = title
            this.done = done ?: false
            this.installId = sc.installId
            this.userId = sc.userId
            this.createdAt = now
            this.updatedAt = now
        }
        return todoRepo.save(sc, todo)
    }

    fun findById(sc: SvcCtx, appId: UUID, id: UUID): Todo? =
        todoRepo.findById(sc, appId, id)

    fun findByCursor(sc: SvcCtx, appId: UUID, cursor: UUID?, limit: Int): List<Todo> =
        todoRepo.findByCursor(sc, appId, cursor, limit)

    fun partialUpdate(sc: SvcCtx, appId: UUID, id: UUID, title: String?, done: Boolean?) {
        todoRepo.partialUpdate(sc, appId, id, title, done)
    }

    fun deleteById(sc: SvcCtx, appId: UUID, id: UUID): Boolean =
        todoRepo.deleteById(sc, appId, id)
}
