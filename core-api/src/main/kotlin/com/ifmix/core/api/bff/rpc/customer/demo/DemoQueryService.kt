package com.ifmix.core.api.bff.rpc.customer.demo

import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.dto.demo.DemoRpcMappers
import com.ifmix.core.api.dto.demo.TodoDto
import com.ifmix.core.api.entity.demo.Todo
import com.ifmix.core.api.entity.demo.TodoItem
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.demo.DemoFacade
import com.ifmix.core.api.modules.demo.repo.TodoItemCounts
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * demo 模块 RPC 聚合层（proposal §5.1）：只注入 [DemoFacade]，禁 import repo/handler
 *（[TodoItemCounts] 是 modules/demo/repo 的纯数据类，S3 mappers 亦直接 import，非跨级调用）。
 *
 * 聚合策略（禁 N+1 循环 findById）：
 * - 单条 = 1 次 findById + 1 次批量 items + 1 次批量 counts；
 * - 批量 = 1 次 findByIds + 1 次 items + 1 次 counts，结果按**入参 ID 顺序**重排
 *   （`findByIds` 返回顺序不作数；库中未命中的 id 静默丢弃，对齐 GraphQL q_demo_findTodosByIds 语义）；
 * - 分页 = 1 次 findByOptions（Page&lt;Todo&gt;）+ 1 次 items + 1 次 counts，保持页内顺序；
 *   dto/common [com.ifmix.core.api.dto.common.CommonFindOptions] 在此经 [DemoRpcMappers.toGenerated]
 *   转 generated 侧（非法 op / sortDirection 抛 ApiError(INVALID_REQUEST)，对齐 mapper 边界）；
 * - counts 缺 key → 填 0（对齐 `TodoItemCountsDataLoader.ZERO` 语义）；items 缺 todo → 空列表。
 *
 * TodoDto 永远带全 items + counts（固定 DTO 决策，proposal §4）。
 */
@Service
class DemoQueryService(private val facade: DemoFacade) {

    private val ZERO_COUNTS = TodoItemCounts(itemCount = 0, pendingCount = 0, finishCount = 0)

    /** 单条：todo + 该条 items + counts（3 次查询）。miss → null（controller 裁决 NOT_FOUND 404000）。 */
    fun findTodoById(ctx: ActionContext, id: UUID): TodoDto? =
        assemble(ctx, facade.findById(ctx, id)?.let { listOf(it) } ?: emptyList()).singleOrNull()

    /** 批量：1 次 findByIds + 1 次 items + 1 次 counts，按入参 ids 顺序组装；入参空 → 空结果（零查询，不查 facade）。 */
    fun findTodosByIds(ctx: ActionContext, ids: List<UUID>): List<TodoDto> {
        if (ids.isEmpty()) return emptyList()
        return assemble(ctx, facade.findByIds(ctx, ids), ids)
    }

    /** 分页：1 次 findByOptions + 1 次 items + 1 次 counts，保持页内顺序。 */
    fun findTodos(ctx: ActionContext, findOptions: com.ifmix.core.api.dto.common.CommonFindOptions?): Page<TodoDto> {
        val page = facade.findTodos(ctx, findOptions?.let { DemoRpcMappers.toGenerated(it) })
        val byId = assemble(ctx, page.items).associateBy { it.id }
        return Page(page.items.map { byId.getValue(it.id) }, page.pageInfo)
    }

    /**
     * 组装：批量查 items/counts 后按 [order]（缺省 = 根查询原顺序）重排。
     * 库中未命中的 id 不出现在结果里；[order] 重复 id 只保留首个（[associateBy] 语义）。
     */
    private fun assemble(ctx: ActionContext, todos: List<Todo>, order: List<UUID>? = null): List<TodoDto> {
        if (todos.isEmpty()) return emptyList()

        val todoIds = todos.map { it.id }
        val itemsByTodoId: Map<UUID, List<TodoItem>> =
            facade.findItemsByTodoIds(ctx, todoIds).groupBy { it.todoId }
        val countsMap: Map<UUID, TodoItemCounts> = facade.countItemsByTodoIds(ctx, todoIds)

        val foundById = todos.associateBy { it.id }
        val sequence: List<Todo> = order?.distinct()?.mapNotNull { foundById[it] } ?: todos
        return sequence.map { todo ->
            DemoRpcMappers.toDto(
                todo,
                itemsByTodoId.getOrDefault(todo.id, emptyList()),
                countsMap[todo.id] ?: ZERO_COUNTS,
            )
        }
    }
}
