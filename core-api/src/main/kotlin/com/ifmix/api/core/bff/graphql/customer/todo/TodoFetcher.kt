package com.ifmix.api.core.bff.graphql.customer.todo

import com.ifmix.api.core.generated.types.CreateTodoInput
import com.ifmix.api.core.generated.types.CreateTodoPayload
import com.ifmix.api.core.generated.types.DeleteTodoPayload
import com.ifmix.api.core.generated.types.TodoPage
import com.ifmix.api.core.generated.types.TodoQueryInput
import com.ifmix.api.core.generated.types.UpdateTodoInput
import com.ifmix.api.core.generated.types.UpdateTodoItemsMutationInput
import com.ifmix.api.core.generated.types.UpdateTodoItemsPayload
import com.ifmix.api.core.generated.types.UpdateTodoPayload
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.jooq.GlobalTxRunner
import com.ifmix.api.core.entity.todo.Todo
import com.ifmix.api.core.entity.todo.TodoItem
import com.ifmix.api.core.dto.scan.AddItemReq
import com.ifmix.api.core.modules.ai.service.ScanCollectionFacadeService
import com.ifmix.api.core.modules.todo.repo.TodoItemRepository
import com.ifmix.api.core.modules.todo.service.TodoFacadeService
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsData
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsDataLoader
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import org.dataloader.MappedBatchLoader
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Todo GraphQL DataFetcher（jOOQ 版）。
 *
 * Query/Mutation 委托给 TodoFacadeService（返回 Domain Model data class）。
 * 关联字段 items 通过 DataLoader 按需批量加载。
 */
@DgsComponent
class TodoFetcher(
    private val todoService: TodoFacadeService,
    private val scanCollectionService: ScanCollectionFacadeService,
    private val ctxProvider: OperationContextProvider,
    private val globalTx: GlobalTxRunner,
) {

    // ==================== Query ====================

    @DgsQuery(field = "query_todo_findTodoById")
    fun findById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): Todo {
        val ctx = ctxProvider.fromDfe(dfe)
        return todoService.findById(ctx, id)
            ?: throw ApiError(ErrorCode.NOT_FOUND)
    }

    @DgsQuery(field = "query_todo_findTodosByCursor")
    fun findByCursor(dfe: DgsDataFetchingEnvironment, @InputArgument input: TodoQueryInput?): TodoPage {
        val ctx = ctxProvider.fromDfe(dfe)
        val page = todoService.findByCursor(ctx, input ?: TodoQueryInput())
        return TodoPage(items = page.items, nextCursor = page.nextCursor, hasMore = page.hasMore)
    }

    @DgsQuery(field = "query_todo_findTodosByIds")
    fun findByIds(dfe: DgsDataFetchingEnvironment, @InputArgument ids: List<UUID>): List<Todo> {
        val ctx = ctxProvider.fromDfe(dfe)
        return todoService.findByIds(ctx, ids)
    }

    // ==================== Sub-field: Todo.items via DataLoader ====================

    @DgsData(parentType = "Todo", field = "items")
    fun todoItems(dfe: DgsDataFetchingEnvironment): CompletableFuture<List<TodoItem>> {
        val todo: Todo = dfe.getSource()!!
        val loader = dfe.getDataLoader<UUID, List<TodoItem>>(TodoItemsDataLoader.NAME)!!
        return loader.load(todo.id)
    }

    // ==================== Mutation: Create ====================

    @DgsMutation(field = "mutation_todo_createTodo")
    fun createTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: CreateTodoInput): CreateTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val id = todoService.createTodo(ctx, input)
        val todo = todoService.findById(ctx, id)
            ?: throw ApiError(ErrorCode.INTERNAL, "Failed to read back created todo")
        return CreateTodoPayload(todo = todo)
    }

    // ==================== Mutation: Update Todo ====================

    @DgsMutation(field = "mutation_todo_updateTodo")
    fun updateTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateTodoInput): UpdateTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val success = todoService.updateTodo(ctx, input)
        // 如果客户端 select 了 todo 字段，回查（readCache=false → 直读主库）
        val todo = if (success && dfe.selectionSet.fields.any { it.name == "todo" }) {
            todoService.findById(ctx, input.id)
        } else null
        return UpdateTodoPayload(success = success, todo = todo)
    }

    // ==================== Mutation: Update TodoItems ====================

    @DgsMutation(field = "mutation_todoItem_batchUpdateTodoItems")
    fun updateTodoItems(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateTodoItemsMutationInput): UpdateTodoItemsPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        todoService.updateItems(ctx, input)
        return UpdateTodoItemsPayload(success = true)
    }

    // ==================== Mutation: Delete ====================

    @DgsMutation(field = "mutation_todo_deleteTodo")
    fun deleteTodoById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): DeleteTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val success = todoService.deleteTodo(ctx, id)
        return DeleteTodoPayload(success = success)
    }

    @DgsMutation(field = "mutation_todo_batchDeleteTodos")
    fun deleteTodosByIds(dfe: DgsDataFetchingEnvironment, @InputArgument ids: List<UUID>): DeleteTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val count = todoService.batchDeleteTodos(ctx, ids)
        return DeleteTodoPayload(success = count == ids.size)
    }

    // ==================== 跨模块事务示例 ====================
    // 使用 GlobalTxRunner 同时操作 todo 和 scan collection
    @DgsMutation(field = "mutation_todo_createTodoAndCollect")
    fun createTodoAndCollect(dfe: DgsDataFetchingEnvironment, @InputArgument input: CreateTodoInput): CreateTodoPayload {
        val opCtx = ctxProvider.fromDfe(dfe)
        return globalTx.withTx(opCtx) { txOpCtx ->
            val id = todoService.createTodo(txOpCtx, input)
            // 跨模块操作共享同一事务：todoService 和 scanCollectionService 的 SvcCtx
            // 都会自动复用 txOpCtx.globalTxDsl，不嵌套开新事务
            val todo = todoService.findById(txOpCtx, id)
                ?: throw ApiError(ErrorCode.INTERNAL, "Failed to read back created todo")
            CreateTodoPayload(todo = todo)
        }
    }
}

/**
 * DataLoader: 批量加载 Todo 的 items 关联。
 * caching=false — 只保留 batching，防止 mutation document 内脏读。
 */
@DgsDataLoader(name = TodoItemsDataLoader.NAME, caching = false)
class TodoItemsDataLoader(private val itemRepo: TodoItemRepository) : MappedBatchLoader<UUID, List<TodoItem>> {

    override fun load(todoIds: Set<UUID>): CompletionStage<Map<UUID, List<TodoItem>>> {
        // ponytail: DataLoader 没有 OperationContext，直接用 DEFAULT。
        // 多集群场景需要从 GraphQLContext 传入 dsl。
        val items = itemRepo.findByTodoIds(SvcCtx.DEFAULT, todoIds)
        val grouped = items.groupBy { it.todoId }
        val result = todoIds.associateWith { grouped[it] ?: emptyList() }
        return CompletableFuture.completedFuture(result)
    }

    companion object {
        const val NAME = "todoItems"
    }
}
