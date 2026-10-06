package com.ifmix.core.api.bff.api.customer.cs

import com.ifmix.core.api.infra.http.ActionSpec
import com.ifmix.core.api.infra.http.ActorRequirement

/**
 * cs 模块 4 个 API action 的 [ActionSpec] 集中定义。
 *
 * 唯一依据是原 [com.ifmix.core.api.bff.graphql.customer.cs.CsFetcher] 的 `ctxProvider.fromDfe(dfe)`
 * 实参——4 个 action 全部默认值：requireAppId=true、requireActorType=ACTOR_CUSTOMER、
 * requireLocale/Country/Currency=false → actor=CUSTOMER、requireProjectId=true。
 */
object CsSpecs {
    val SUBMIT_FEEDBACK = ActionSpec("m_cs_feedback_createOne", isMutation = true, actor = ActorRequirement.CUSTOMER)
    val CREATE_SUPPORT_REQUEST = ActionSpec("m_cs_supportRequest_createOne", isMutation = true, actor = ActorRequirement.CUSTOMER)
    val MY_SUPPORT_REQUEST_BY_ID = ActionSpec("q_cs_supportRequest_getById", isMutation = false, actor = ActorRequirement.CUSTOMER)
    val MY_SUPPORT_REQUESTS = ActionSpec("q_cs_supportRequest_list", isMutation = false, actor = ActorRequirement.CUSTOMER)
}
