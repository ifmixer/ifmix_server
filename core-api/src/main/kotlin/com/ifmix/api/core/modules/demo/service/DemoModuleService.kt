package com.ifmix.api.core.modules.demo.service

import com.ifmix.api.core.generated.types.*
import com.ifmix.api.core.infra.db.SvcCtxFactory
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.mybatis.MyBatisTxRunner
import com.ifmix.api.core.modules.demo.repo.TodoItemRepository
import com.ifmix.api.core.modules.demo.repo.TodoRepository
import com.ifmix.api.core.entity.demo.Todo
import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.modules.demo.service.internal.TodoEntityService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Demo 模块服务 — 使用 MyBatis 访问 core_todo / core_todo_item。
 */
@Service
class DemoModuleService(
    private val svcCtxFactory: SvcCtxFactory,
    private val todoRepo: TodoRepository,
    private val todoItemRepo: TodoItemRepository,
    private val tx: MyBatisTxRunner,
) {

    fun findById(opCtx: OperationContext, id: UUID): Todo? =
        svcCtxFactory.forApp(opCtx).use { ctx ->
            todoRepo.findById(ctx, opCtx.mustGetAppId(), id)
        }

    fun findTodosByCursor(opCtx: OperationContext, input: TodoQueryInput): Page<Todo> {
        val ctx = svcCtxFactory.forApp(opCtx)
        val limit = input.limit ?: 20
        val cursorStr = input.cursor
        val cursor = cursorStr?.let { try { UUID.fromString(it) } catch (e: Exception) { null } }
        val rawItems = ctx.use { todoRepo.findByCursor(it, opCtx.mustGetAppId(), cursor, limit + 1) }
        return Page.of(rawItems, limit) { it.id.toString() }
    }

    fun findTodosByIds(opCtx: OperationContext, ids: Collection<UUID>): List<Todo> {
        val ctx = svcCtxFactory.forApp(opCtx)
        return ctx.use { todoRepo.findByIds(it, ids) }
    }

    fun createTodo(opCtx: OperationContext, input: CreateTodoInput): Todo {
        val appId = opCtx.mustGetAppId()
        val todo = Todo(
            id = UuidV7.generate(),
            appId = appId,
            title = input.title,
            done = input.done ?: false,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now(),
        )
        val ctx = svcCtxFactory.forApp(opCtx)
        return tx.withTx(ctx) { txCtx ->
            todoRepo.insert(txCtx, todo)
            // 创建子项
            input.items?.forEach { itemInput ->
                val itemId = UuidV7.generate()
                val item = TodoEntityService.toTodoItem(todo.id, itemInput.content, itemInput.done ?: false)
                todoItemRepo.insert(txCtx, item)
            }
            todo
        }
    }

    fun updateTodo(opCtx: OperationContext, input: UpdateTodoInput): UpdateTodoPayload {
        val appId = opCtx.mustGetAppId()
        val ctx = svcCtxFactory.forApp(opCtx)
        return tx.withTx(ctx) { txCtx ->
            val existing = todoRepo.findById(txCtx, appId, input.id)
                ?: throw com.ifmix.api.core.infra.http.ApiError(com.ifmix.api.core.infra.http.ErrorCode.NOT_FOUND)
            val updated = existing.copy(
                title = input.set?.title ?: existing.title,
                done = input.set?.done ?: existing.done,
            )
            todoRepo.deleteById(txCtx, appId, input.id)
            todoRepo.insert(txCtx, updated)
            UpdateTodoPayload(success = true, todo = updated)
        }
    }

    fun deleteTodo(opCtx: OperationContext, id: UUID): DeleteTodoPayload {
        val appId = opCtx.mustGetAppId()
        val ctx = svcCtxFactory.forApp(opCtx)
        return tx.withTx(ctx) { txCtx ->
            val deleted = todoRepo.deleteById(txCtx, appId, id)
            DeleteTodoPayload(success = deleted)
        }
    }

    fun batchDeleteTodos(opCtx: OperationContext, ids: Collection<UUID>): DeleteTodoPayload {
        val appId = opCtx.mustGetAppId()
        var successCount = 0
        val ctx = svcCtxFactory.forApp(opCtx)
        tx.withTx(ctx) { txCtx ->
            ids.forEach { id ->
                if (todoRepo.deleteById(txCtx, appId, id)) successCount++
            }
        }
        return DeleteTodoPayload(success = successCount > 0)
    }

    fun batchUpdateTodoItems(opCtx: OperationContext, input: UpdateTodoItemsMutationInput): UpdateTodoItemsPayload {
        val appId = opCtx.mustGetAppId()
        val ctx = svcCtxFactory.forApp(opCtx)
        tx.withTx(ctx) { txCtx ->
            input.create?.forEach { itemInput ->
                val itemId = UuidV7.generate()
                val item = TodoEntityService.toTodoItem(itemInput.todoId, itemInput.content, itemInput.done ?: false)
                todoItemRepo.insert(txCtx, item)
            }
            input.update?.forEach { itemInput ->
                val existing = todoItemRepo.findById(txCtx, appId, itemInput.id)
                    ?: return@forEach
                val updated = existing.copy(
                    content = itemInput.set?.content ?: existing.content,
                    done = itemInput.set?.done ?: existing.done,
                )
                todoItemRepo.deleteById(txCtx, appId, itemInput.id)
                todoItemRepo.insert(txCtx, updated)
            }
            input.delete?.forEach { id ->
                todoItemRepo.deleteById(txCtx, appId, id)
            }
        }
        return UpdateTodoItemsPayload(success = true)
    }
}
