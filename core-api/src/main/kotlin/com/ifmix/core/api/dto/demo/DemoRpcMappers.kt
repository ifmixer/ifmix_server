package com.ifmix.core.api.dto.demo

import com.ifmix.core.api.dto.common.CommonFindOptions
import com.ifmix.core.api.dto.common.FieldFilter
import com.ifmix.core.api.dto.common.FilterExpr
import com.ifmix.core.api.dto.common.FilterGroup
import com.ifmix.core.api.entity.demo.Todo
import com.ifmix.core.api.entity.demo.TodoItem
import com.ifmix.core.api.entity.demo.TodoRecommend
import com.ifmix.core.api.entity.demo.toDomain
import com.ifmix.core.api.generated.types.CreateTodoItemForTodoInput
import com.ifmix.core.api.generated.types.CreateTodoItemInput
import com.ifmix.core.api.generated.types.CreateTodoInput
import com.ifmix.core.api.generated.types.UpdateTodoInput
import com.ifmix.core.api.generated.types.UpdateTodoItemsMutationInput
import com.ifmix.core.api.generated.types.UpdateTodoItemInput
import com.ifmix.core.api.generated.types.TodoItemUnsetField
import com.ifmix.core.api.generated.types.TodoRecItemInput
import com.ifmix.core.api.generated.types.TodoRecommendInput
import com.ifmix.core.api.generated.types.TodoUnsetField
import com.ifmix.core.api.generated.types.UpdateTodoSetInput
import com.ifmix.core.api.generated.types.UpdateTodoItemSetInput
import com.ifmix.core.api.generated.types.FilterExpr as GenFilterExpr
import com.ifmix.core.api.generated.types.FilterGroup as GenFilterGroup
import com.ifmix.core.api.generated.types.FilterOp
import com.ifmix.core.api.generated.types.FieldFilter as GenFieldFilter
import com.ifmix.core.api.generated.types.CommonFindOptions as GenCommonFindOptions
import com.ifmix.core.api.generated.types.SortDirection
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.modules.demo.repo.TodoItemCounts
import java.time.Instant

/**
 * demo 模块 RPC mapper（纯 object，无 Spring 依赖）。
 *
 * 两类映射，全部显式手写：
 * - DTO → generated（入参侧，供 controller 调现有 Facade/handler）
 * - entity → DTO（出参侧，DateTime 统一 [Instant.toString] ISO-8601 UTC）
 *
 * 非法字符串（unset 字段 / filter op / sortDirection）抛
 * [ApiError]([ErrorCode.INVALID_REQUEST])。
 */
object DemoRpcMappers {

    // ===== DTO → generated（入参侧） =====

    /** [CreateTodoInputDto] → generated [com.ifmix.core.api.generated.types.CreateTodoInput] */
    fun toGenerated(input: CreateTodoInputDto): CreateTodoInput =
        CreateTodoInput(
            title = input.title,
            done = input.done,
            note = input.note,
            recommend = input.recommend?.let { toGeneratedTodoRecommendInput(it) },
            items = input.items?.map {
                CreateTodoItemInput(content = it.content, done = it.done, note = it.note)
            },
        )

    /** [TodoRecommendInputDto] → generated [TodoRecommendInput] */
    fun toGenerated(input: TodoRecommendInputDto): TodoRecommendInput = toGeneratedTodoRecommendInput(input)

    private fun toGeneratedTodoRecommendInput(input: TodoRecommendInputDto): TodoRecommendInput =
        TodoRecommendInput(
            sectionId = input.sectionId,
            sectionName = input.sectionName,
            viewCount = input.viewCount,
            recItems = input.recItems?.map {
                TodoRecItemInput(
                    recId = it.recId,
                    title = it.title,
                    priority = it.priority,
                    createdAt = requireInstant(it.createdAt, "recItem.createdAt"),
                    updatedAt = it.updatedAt?.let { s -> requireInstant(s, "recItem.updatedAt") },
                )
            },
        )

    /** [UpdateTodoInputDto] → generated [UpdateTodoInput]（unset 字符串转 [TodoUnsetField]） */
    fun toGenerated(input: UpdateTodoInputDto): UpdateTodoInput =
        UpdateTodoInput(
            id = input.id,
            set = input.set?.let { s ->
                UpdateTodoSetInput(
                    title = s.title,
                    done = s.done,
                    note = s.note,
                    recommend = s.recommend?.let { toGeneratedTodoRecommendInput(it) },
                )
            },
            unset = input.unset?.map { requireTodoUnsetField(it) },
        )

    /** [UpdateTodoItemsMutationInputDto] → generated [UpdateTodoItemsMutationInput] */
    fun toGenerated(input: UpdateTodoItemsMutationInputDto): UpdateTodoItemsMutationInput =
        UpdateTodoItemsMutationInput(
            create = input.create?.map {
                CreateTodoItemForTodoInput(todoId = it.todoId, content = it.content, done = it.done, note = it.note)
            },
            update = input.update?.map { toGeneratedItem(it) },
            delete = input.delete,
        )

    private fun toGeneratedItem(input: UpdateTodoItemInputDto): UpdateTodoItemInput =
        UpdateTodoItemInput(
            id = input.id,
            set = input.set?.let { s ->
                UpdateTodoItemSetInput(content = s.content, done = s.done, note = s.note)
            },
            unset = input.unset?.map { requireTodoItemUnsetField(it) },
        )

    /**
     * [CommonFindOptions]（dto/common）→ generated [GenCommonFindOptions]。
     * 含 FilterGroup 递归与 FilterOp 字符串转枚举；非法 op / sortDirection 抛 INVALID_REQUEST。
     */
    fun toGenerated(input: CommonFindOptions): GenCommonFindOptions =
        GenCommonFindOptions(
            filter = input.filter?.let { toGenFilterGroup(it) },
            cursor = input.cursor,
            sortBy = input.sortBy,
            sortDirection = input.sortDirection?.let { requireSortDirection(it) },
            limit = input.limit,
        )

    private fun toGenFilterGroup(group: FilterGroup): GenFilterGroup =
        GenFilterGroup(
            and = group.and?.map { toGenFilterExpr(it) },
            or = group.or?.map { toGenFilterExpr(it) },
        )

    private fun toGenFilterExpr(expr: FilterExpr): GenFilterExpr =
        GenFilterExpr(
            field = expr.field?.let { toGenFieldFilter(it) },
            group = expr.group?.let { toGenFilterGroup(it) },
        )

    private fun toGenFieldFilter(f: FieldFilter): GenFieldFilter =
        GenFieldFilter(
            field = f.field,
            op = requireFilterOp(f.op),
            value = f.value,
            values = f.values,
        )

    /**
     * [TodoRecommendInputDto] → domain [TodoRecommend]（复用 generated types 里已有的
     * [com.ifmix.core.api.entity.demo.toDomain] 语义，与 TodoAggHandler/TodoRepository 一致）。
     */
    fun recommendToDomain(input: TodoRecommendInputDto): TodoRecommend =
        toGeneratedTodoRecommendInput(input).toDomain()

    // ===== entity → DTO（出参侧） =====

    /**
     * Todo entity + 批量 items + counts → [TodoDto]。
     * 聚合在调用方完成（先查根分页、批量查 items/counts 再按 Map 组装，禁止循环 findById）。
     */
    fun toDto(todo: Todo, items: List<TodoItem>, counts: TodoItemCounts): TodoDto =
        TodoDto(
            id = todo.id,
            title = todo.title,
            done = todo.done,
            note = todo.note,
            meta = todo.meta,
            recommend = todo.recommend?.let { recommendToDto(it) },
            items = items.map { toDto(it) },
            itemCount = counts.itemCount,
            pendingCount = counts.pendingCount,
            finishCount = counts.finishCount,
            createdAt = todo.createdAt.toString(),
            updatedAt = todo.updatedAt?.toString(),
        )

    fun toDto(item: TodoItem): TodoItemDto =
        TodoItemDto(
            id = item.id,
            content = item.content,
            done = item.done,
            note = item.note,
            createdAt = item.createdAt.toString(),
            updatedAt = item.updatedAt?.toString(),
        )

    fun recommendToDto(recommend: TodoRecommend): TodoRecommendDto =
        TodoRecommendDto(
            sectionId = recommend.sectionId,
            sectionName = recommend.sectionName,
            viewCount = recommend.viewCount,
            recItems = recommend.recItems?.map { recItemToDto(it) },
        )

    fun recItemToDto(item: TodoRecommend.RecItem): TodoRecItemDto =
        TodoRecItemDto(
            recId = item.recId,
            title = item.title,
            priority = item.priority,
            createdAt = item.createdAt.toString(),
            updatedAt = item.updatedAt?.toString(),
        )

    // ===== 校验（非法值抛 INVALID_REQUEST） =====

    private fun requireFilterOp(op: String): FilterOp =
        runCatching { FilterOp.valueOf(op) }.getOrNull()
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "invalid filter op: $op")

    private fun requireTodoUnsetField(field: String): TodoUnsetField =
        runCatching { TodoUnsetField.valueOf(field) }.getOrNull()
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "invalid unset field: $field")

    private fun requireTodoItemUnsetField(field: String): TodoItemUnsetField =
        runCatching { TodoItemUnsetField.valueOf(field) }.getOrNull()
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "invalid unset field: $field")

    private fun requireSortDirection(direction: String): SortDirection =
        runCatching { SortDirection.valueOf(direction) }.getOrNull()
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "invalid sortDirection: $direction")

    /** 实体侧 DateTime 非空但缺省为 ISO-8601 字符串时解析（recItem.createdAt 在 schema 中为必填）。 */
    private fun requireInstant(s: String?, name: String): Instant =
        runCatching { Instant.parse(s) }.getOrNull()
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "invalid or missing DateTime: $name = $s")
}
