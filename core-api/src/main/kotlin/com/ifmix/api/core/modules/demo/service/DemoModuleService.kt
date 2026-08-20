package com.ifmix.api.core.modules.demo.service

import com.ifmix.api.core.generated.types.*
import com.ifmix.api.core.infra.db.SvcCtxFactory
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.mybatis.MyBatisTxRunner
import com.ifmix.api.core.modules.demo.repo.TodoItemRepository
import com.ifmix.api.core.modules.demo.repo.TodoRepository
import com.ifmix.api.core.modules.demo.mybatis.model.CoreTodo
import com.ifmix.api.core.modules.demo.mybatis.model.CoreTodoItem
import com.ifmix.api.core.dto.common.Page
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Demo 模块服务 — 直接使用 MBG 生成的 CoreTodo / CoreTodoItem 作为领域模型。
 */
@Service
class DemoModuleService(
    private val svcCtxFactory: SvcCtxFactory,
    private val todoRepo: TodoRepository,
    private val todoItemRepo: TodoItemRepository,
    private val tx: MyBatisTxRunner,
) {

    fun findById(opCtx: OperationContext, id: UUID): CoreTodo? =
        svcCtxFactory.forApp(opCtx).use { ctx ->
            todoRepo.findById(ctx, opCtx.mustGetAppId(), id)
        }

    fun findTodosByCursor(opCtx: OperationContext, input: TodoQueryInput): Page<CoreTodo> =
        svcCtxFactory.forApp(opCtx).use { ctx ->
            val limit = (input.limit ?: 20).coerceIn(1, 100)
            val cursor = input.cursor?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val rawItems = todoRepo.findByCursor(ctx, opCtx.mustGetAppId(), cursor, limit + 1)
            Page.of(rawItems, limit) { it.id.toString() }
        }

    fun findTodosByIds(opCtx: OperationContext, ids: Collection<UUID>): List<CoreTodo> =
        svcCtxFactory.forApp(opCtx).use { ctx ->
            todoRepo.findByIds(ctx, opCtx.mustGetAppId(), ids)
        }

    fun createTodo(opCtx: OperationContext, input: CreateTodoInput): CoreTodo =
        svcCtxFactory.forApp(opCtx).use { ctx ->
            tx.withTx(ctx) { txCtx ->
                val appId = opCtx.mustGetAppId()
                val now = Instant.now()
                val todo = CoreTodo(
                    id = UuidV7.generate(),
                    appId = appId,
                    installId = opCtx.installId,
                    userId = opCtx.userId,
                    title = input.title,
                    done = input.done ?: false,
                    createdAt = now,
                    updatedAt = now,
                )
                todoRepo.insert(txCtx, todo)

                input.items?.forEach { itemInput ->
                    val item = CoreTodoItem(
                        id = UuidV7.generate(),
                        appId = appId,
                        todoId = todo.id,
                        content = itemInput.content,
                        done = itemInput.done ?: false,
                        createdAt = now,
                        updatedAt = now,
                    )
                    todoItemRepo.insert(txCtx, item)
                }
                todo
            }
        }

    fun updateTodo(opCtx: OperationContext, input: UpdateTodoInput): UpdateTodoPayload =
        svcCtxFactory.forApp(opCtx).use { ctx ->
            tx.withTx(ctx) { txCtx ->
                val appId = opCtx.mustGetAppId()
                if (!todoRepo.exists(txCtx, appId, input.id)) {
                    throw ApiError(ErrorCode.NOT_FOUND, "Todo not found: ${input.id}")
                }
                todoRepo.update(txCtx, appId, input)
                val updated = todoRepo.findById(txCtx, appId, input.id)
                UpdateTodoPayload(success = true, todo = updated)
            }
        }

    fun deleteTodo(opCtx: OperationContext, id: UUID): DeleteTodoPayload =
        svcCtxFactory.forApp(opCtx).use { ctx ->
            tx.withTx(ctx) { txCtx ->
                val deleted = todoRepo.deleteById(txCtx, opCtx.mustGetAppId(), id)
                DeleteTodoPayload(success = deleted)
            }
        }

    fun batchDeleteTodos(opCtx: OperationContext, ids: Collection<UUID>): DeleteTodoPayload =
        svcCtxFactory.forApp(opCtx).use { ctx ->
            tx.withTx(ctx) { txCtx ->
                val appId = opCtx.mustGetAppId()
                var count = 0
                ids.forEach { if (todoRepo.deleteById(txCtx, appId, it)) count++ }
                DeleteTodoPayload(success = count > 0)
            }
        }

    fun batchUpdateTodoItems(opCtx: OperationContext, input: UpdateTodoItemsMutationInput): UpdateTodoItemsPayload =
        svcCtxFactory.forApp(opCtx).use { ctx ->
            tx.withTx(ctx) { txCtx ->
                val appId = opCtx.mustGetAppId()
                val now = Instant.now()

                input.create?.forEach { itemInput ->
                    val item = CoreTodoItem(
                        id = UuidV7.generate(),
                        appId = appId,
                        todoId = itemInput.todoId,
                        content = itemInput.content,
                        done = itemInput.done ?: false,
                        createdAt = now,
                        updatedAt = now,
                    )
                    todoItemRepo.insert(txCtx, item)
                }

                input.update?.forEach { itemInput ->
                    todoItemRepo.update(txCtx, appId, itemInput)
                }

                input.delete?.forEach { itemId ->
                    todoItemRepo.deleteById(txCtx, appId, itemId)
                }

                UpdateTodoItemsPayload(success = true)
            }
        }
}
