package com.ifmix.core.api.bff.api.customer.install

import com.ifmix.core.api.infra.http.ActionSpec

/**
 * install 模块 ActionSpec（唯一依据 = InstallFetcher 各 action 的 `fromDfe(dfe, requireActorType = null)` 实参）：
 * 全部为 requireActorType=null（token 可选/自验）；token 要求由各 endpoint 的既有内部校验精确执行
 *（updateInstall 需可信 iid、attestExisting 严格只认 installToken、createInstall/challenge/recover 无 token）。
 */
object InstallSpecs {
    val CREATE_INSTALL = ActionSpec(reqName = "m_install_install_create", isMutation = true, actor = com.ifmix.core.api.infra.http.ActorRequirement.NONE)
    val UPDATE_INSTALL = ActionSpec(reqName = "m_install_install_updateOne", isMutation = true, actor = com.ifmix.core.api.infra.http.ActorRequirement.NONE)
    val ATTEST_EXISTING = ActionSpec(reqName = "m_install_install_attest", isMutation = true, actor = com.ifmix.core.api.infra.http.ActorRequirement.NONE)
    val RECOVER_INSTALL = ActionSpec(reqName = "m_install_install_recover", isMutation = true, actor = com.ifmix.core.api.infra.http.ActorRequirement.NONE)
    val CREATE_ATTEST_CHALLENGE = ActionSpec(reqName = "m_install_install_createAttestChallenge", isMutation = true, actor = com.ifmix.core.api.infra.http.ActorRequirement.NONE)
}
