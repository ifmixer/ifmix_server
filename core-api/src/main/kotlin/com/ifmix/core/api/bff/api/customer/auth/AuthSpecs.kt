package com.ifmix.core.api.bff.api.customer.auth

import com.ifmix.core.api.infra.http.ActionSpec

/**
 * auth 模块 ActionSpec（唯一依据 = AuthFetcher 各 action 的 fromDfe 实参）：
 * - login/refresh：`fromDfe(dfe, requireActorType = null)` → NONE（token 可选自验；token 要求由
 *   `mustGetLoginInstallId` / `mustGetTokenInstallId` 精确执行——refresh 的 access token 允许过期/缺失）。
 * - logout/me/deleteAccount：`fromDfe(dfe)` 全默认 → CUSTOMER。
 */
object AuthSpecs {
    val LOGIN = ActionSpec(reqName = "m_auth_session_login", isMutation = true, actor = com.ifmix.core.api.infra.http.ActorRequirement.NONE)
    val REFRESH = ActionSpec(reqName = "m_auth_session_refresh", isMutation = true, actor = com.ifmix.core.api.infra.http.ActorRequirement.NONE)
    val LOGOUT = ActionSpec(reqName = "m_auth_session_logout", isMutation = true, actor = com.ifmix.core.api.infra.http.ActorRequirement.CUSTOMER)
    val ME = ActionSpec(reqName = "q_auth_session_me", isMutation = false, actor = com.ifmix.core.api.infra.http.ActorRequirement.CUSTOMER)
    val DELETE_ACCOUNT = ActionSpec(reqName = "m_auth_account_deleteOne", isMutation = true, actor = com.ifmix.core.api.infra.http.ActorRequirement.CUSTOMER)
}
