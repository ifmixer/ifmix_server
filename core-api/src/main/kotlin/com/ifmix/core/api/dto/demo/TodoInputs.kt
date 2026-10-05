package com.ifmix.core.api.dto.demo

/** 与 GraphQL argument 名一一对应（客户端 variables 原样搬进 input）。 */
data class FindTodoByIdInput(val id: java.util.UUID)
data class FindTodosByIdsInput(val ids: List<java.util.UUID>)
data class FindTodosInput(val findOptions: com.ifmix.core.api.dto.common.CommonFindOptions?)
data class CreateTodoInputDto(
    val title: String, val done: Boolean? = null, val note: String? = null,
    val recommend: TodoRecommendInputDto? = null, val items: List<CreateTodoItemInputDto>? = null,
)
data class CreateTodoItemInputDto(val content: String, val done: Boolean? = null, val note: String? = null)
data class TodoRecommendInputDto(
    val sectionId: java.util.UUID, val sectionName: String, val viewCount: Int? = null,
    val recItems: List<TodoRecItemInputDto>? = null,
)
/** 客户端不提交 createdAt/updatedAt（服务端生成字段；客户端 rpcDemo.ts 用 Omit 对齐）。
 *  注意与 GraphQL input 的差异：GraphQL 的 TodoRecItemInput.createdAt 是必填（老客户端自己造时间戳），
 *  RPC 契约改为服务端 mapper 统一打戳，见 §4.4。 */
data class TodoRecItemInputDto(
    val recId: java.util.UUID, val title: String? = null, val priority: Int,
)
data class UpdateTodoInputDto(val id: java.util.UUID, val set: UpdateTodoSetInputDto?, val unset: List<String>? = null)
data class UpdateTodoSetInputDto(val title: String? = null, val done: Boolean? = null, val note: String? = null, val recommend: TodoRecommendInputDto? = null)
data class UpdateTodoItemsMutationInputDto(
    val create: List<CreateTodoItemForTodoInputDto>? = null,
    val update: List<UpdateTodoItemInputDto>? = null,
    val delete: List<java.util.UUID>? = null,
)
data class CreateTodoItemForTodoInputDto(val todoId: java.util.UUID, val content: String, val done: Boolean? = null, val note: String? = null)
data class UpdateTodoItemInputDto(val id: java.util.UUID, val set: UpdateTodoItemSetInputDto?, val unset: List<String>? = null)
data class UpdateTodoItemSetInputDto(val content: String? = null, val done: Boolean? = null, val note: String? = null)
