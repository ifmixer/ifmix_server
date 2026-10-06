package com.ifmix.core.api.bff.api.customer.auth

import com.ifmix.core.api.infra.http.ActionSpec
import com.ifmix.core.api.infra.http.ActorRequirement

/**
 * customer 模块 API action 的 [ActionSpec]。
 *
 * 唯一依据是原 [com.ifmix.core.api.bff.graphql.customer.customer.CustomerFetcher] 的
 * `ctxProvider.fromDfe(dfe, requireActorType = null)`：requireAppId=true（requireProjectId=true）、
 * 不要求登录但带 token 照校验——install token / customer token 皆可（createAnonymous 只要求
 * 可信 iid）→ [ActorRequirement.INSTALL_OR_CUSTOMER]。
 */
object CustomerSpecs {
    val CREATE_ANONYMOUS = ActionSpec(
        "m_auth_customer_createAnonymous",
        isMutation = true,
        actor = ActorRequirement.INSTALL_OR_CUSTOMER,
    )
}
