package com.ifmix.core.api.bff.rpc.customer.demo

import com.ifmix.core.api.infra.http.ActionSpec
import com.ifmix.core.api.infra.http.ActorRequirement

/**
 * demo 模块 8 个 RPC action 的 [ActionSpec] 集中定义（S4 §5.2）。
 *
 * 全部 CUSTOMER + requireProjectId=true（§3.4 裁决表：demo 是 customer 资源）。
 * reqName 带 q_/m_ 前缀、与 GraphQL 字段名一致（[com.ifmix.core.api.bff.graphql.customer.demo.DemoFetcher] 的
 * @DgsQuery/@DgsMutation field），客户端 rpcDemo.ts 的 8 个 action 名与其一一对应（rpc-pilot-client.md §1）。
 */
object DemoSpecs {
    val FIND_TODO_BY_ID = ActionSpec("q_demo_todo_getById", isMutation = false, actor = ActorRequirement.CUSTOMER)
    val FIND_TODOS_BY_IDS = ActionSpec("q_demo_todo_getByIds", isMutation = false, actor = ActorRequirement.CUSTOMER)
    val FIND_TODOS = ActionSpec("q_demo_todo_list", isMutation = false, actor = ActorRequirement.CUSTOMER)
    val CREATE_TODO = ActionSpec("m_demo_todo_createOne", isMutation = true, actor = ActorRequirement.CUSTOMER)
    val UPDATE_TODO = ActionSpec("m_demo_todo_updateOne", isMutation = true, actor = ActorRequirement.CUSTOMER)
    val BATCH_UPDATE_TODO_ITEMS = ActionSpec("m_demo_todo_updateItems", isMutation = true, actor = ActorRequirement.CUSTOMER)
    val DELETE_TODO = ActionSpec("m_demo_todo_deleteOne", isMutation = true, actor = ActorRequirement.CUSTOMER)
    val DELETE_TODO_BY_IDS = ActionSpec("m_demo_todo_deleteMany", isMutation = true, actor = ActorRequirement.CUSTOMER)
}
