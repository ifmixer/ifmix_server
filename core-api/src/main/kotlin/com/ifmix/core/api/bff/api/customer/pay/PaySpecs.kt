package com.ifmix.core.api.bff.api.customer.pay

import com.ifmix.core.api.infra.http.ActionSpec
import com.ifmix.core.api.infra.http.ActorRequirement

/**
 * pay 模块 API action 的 [ActionSpec]。
 *
 * 唯一依据是原 [com.ifmix.core.api.bff.graphql.customer.pay.PaymentFetcher] 的
 * `ctxProvider.fromDfe(dfe)` 实参——全部默认值：requireAppId=true、requireActorType=ACTOR_CUSTOMER、
 * requireLocale/Country/Currency=false → actor=CUSTOMER、requireProjectId=true。
 */
object PaySpecs {
    val IAP_VERIFY = ActionSpec("m_pay_iap_verify", isMutation = true, actor = ActorRequirement.CUSTOMER)
}
