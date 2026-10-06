package com.ifmix.core.api.bff.api.customer.ai

import com.ifmix.core.api.infra.http.ActionSpec
import com.ifmix.core.api.infra.http.ActorRequirement

/**
 * ai 模块 13 个 RPC action 的 [ActionSpec] 集中定义（仿 demo DemoSpecs；命名总表
 * rpc-rollout-client.md §1 R3 行：旧 GraphQL 字段名 → 新 actionName 一一对应）。
 *
 * 全部 CUSTOMER + requireProjectId=true（scan/collection/deepResearch 均为 customer 资源，
 * 与 AiFetcher.fromDfe 的 requireAppId=true / requireActorType=ACTOR_CUSTOMER 对齐）。
 */
object AiSpecs {
    // ---- Scan queries（旧 q_ai_findMyScanById / q_ai_findMyScans / q_ai_getScanStatus）----
    val SCAN_GET_BY_ID = ActionSpec("q_ai_scan_getById", isMutation = false, actor = ActorRequirement.CUSTOMER)
    val SCAN_LIST = ActionSpec("q_ai_scan_list", isMutation = false, actor = ActorRequirement.CUSTOMER)
    val SCAN_GET_STATUS = ActionSpec("q_ai_scan_getStatus", isMutation = false, actor = ActorRequirement.CUSTOMER)

    // ---- DeepResearch（旧 m_ai_runDeepResearch / q_ai_getDeepResearchStatus）----
    val DEEP_RESEARCH_RUN = ActionSpec("m_ai_deepResearch_run", isMutation = true, actor = ActorRequirement.CUSTOMER)
    val DEEP_RESEARCH_GET_STATUS = ActionSpec("q_ai_deepResearch_getStatus", isMutation = false, actor = ActorRequirement.CUSTOMER)

    // ---- Collection（旧 q_ai_getDefaultCollection / m_ai_addCollectionItem / m_ai_removeCollectionItems / q_ai_findCollectionItemsByCursor）----
    val COLLECTION_GET_DEFAULT = ActionSpec("q_ai_collection_getDefault", isMutation = false, actor = ActorRequirement.CUSTOMER)
    val COLLECTION_ITEM_ADD = ActionSpec("m_ai_collectionItem_add", isMutation = true, actor = ActorRequirement.CUSTOMER)
    val COLLECTION_ITEM_REMOVE_MANY = ActionSpec("m_ai_collectionItem_removeMany", isMutation = true, actor = ActorRequirement.CUSTOMER)
    val COLLECTION_ITEM_LIST = ActionSpec("q_ai_collectionItem_list", isMutation = false, actor = ActorRequirement.CUSTOMER)

    // ---- Scan mutations（旧 m_ai_createScan / m_ai_updateScan / m_ai_deleteScan / m_ai_batchUpdateScan）----
    val SCAN_CREATE_ONE = ActionSpec("m_ai_scan_createOne", isMutation = true, actor = ActorRequirement.CUSTOMER)
    val SCAN_UPDATE_ONE = ActionSpec("m_ai_scan_updateOne", isMutation = true, actor = ActorRequirement.CUSTOMER)
    val SCAN_DELETE_ONE = ActionSpec("m_ai_scan_deleteOne", isMutation = true, actor = ActorRequirement.CUSTOMER)
    val SCAN_UPDATE_MANY = ActionSpec("m_ai_scan_updateMany", isMutation = true, actor = ActorRequirement.CUSTOMER)
}
