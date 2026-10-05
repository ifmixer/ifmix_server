package com.ifmix.core.api.dto.demo

/** 与 GraphQL argument 名一一对应（客户端 variables 原样搬进 input）。 */
data class FindTodoByIdInput(val id: java.util.UUID)
data class FindTodosByIdsInput(val ids: List<java.util.UUID>)
data class FindTodosInput(val findOptions: com.ifmix.core.api.dto.common.CommonFindOptions?)
data class CreateTodoInput(
    val title: String, val done: Boolean? = null, val note: String? = null,
    val recommend: TodoRecommendInput? = null, val items: List<CreateTodoItemInput>? = null,
)
data class CreateTodoItemInput(val content: String, val done: Boolean? = null, val note: String? = null)
data class TodoRecommendInput(
    val sectionId: java.util.UUID, val sectionName: String, val viewCount: Int? = null,
    val recItems: List<TodoRecItemInput>? = null,
)
/** 客户端不提交 createdAt/updatedAt（服务端生成字段；客户端 rpcDemo.ts 用 Omit 对齐）。
 *  注意与 GraphQL input 的差异：GraphQL 的 TodoRecItemInput.createdAt 是必填（老客户端自己造时间戳），
 *  RPC 契约改为服务端 mapper 统一打戳，见 §4.4。 */
data class TodoRecItemInput(
    val recId: java.util.UUID, val title: String? = null, val priority: Int,
)
data class UpdateTodoInput(val id: java.util.UUID, val set: UpdateTodoSetInput?, val unset: List<String>? = null)
data class UpdateTodoSetInput(val title: String? = null, val done: Boolean? = null, val note: String? = null, val recommend: TodoRecommendInput? = null)
data class UpdateTodoItemsMutationInput(
    val create: List<CreateTodoItemForTodoInput>? = null,
    val update: List<UpdateTodoItemInput>? = null,
    val delete: List<java.util.UUID>? = null,
)
data class CreateTodoItemForTodoInput(val todoId: java.util.UUID, val content: String, val done: Boolean? = null, val note: String? = null)
data class UpdateTodoItemInput(val id: java.util.UUID, val set: UpdateTodoItemSetInput?, val unset: List<String>? = null)
data class UpdateTodoItemSetInput(val content: String? = null, val done: Boolean? = null, val note: String? = null)

val TODO_UNSET_FIELDS = setOf("NOTE", "RECOMMEND")
val TODO_ITEM_UNSET_FIELDS = setOf("NOTE")

/** 非法 unset 字段抛 INVALID_REQUEST（handler 入口调用；纯函数便于单测）。 */
fun requireUnsetFields(unset: List<String>?, allowed: Set<String>) {
    unset?.find { it !in allowed }?.let {
        throw com.ifmix.core.api.infra.http.ApiError(
            com.ifmix.core.api.infra.http.ErrorCode.INVALID_REQUEST, "invalid unset field: $it")
    }
}
