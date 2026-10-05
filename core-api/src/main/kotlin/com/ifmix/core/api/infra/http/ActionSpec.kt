package com.ifmix.core.api.infra.http

/**
 * 端点对主体的要求（[ActionContextFactory] 按 §3.4 表直接解释，不映射到 RequestParser 参数）：
 * - [NONE]：不要求登录；带了 token 也照校验（过期/无效仍报错），但校验通过与否不要求有 actor。
 * - [INSTALL_OR_CUSTOMER]：install token（type=5）或 customer 皆可。
 * - [CUSTOMER]：必须 customer 身份（install token → UNAUTHORIZED，manager token → FORBIDDEN）。
 *
 * demo 8 个 action 全部为 CUSTOMER；另两档为后续模块预留，本试点不使用。
 */
enum class ActorRequirement { NONE, INSTALL_OR_CUSTOMER, CUSTOMER }

/**
 * 单个 RPC action 的规格：路由名、读写属性、主体要求、是否必须 projectId。
 * 由 [ActionContextFactory] 解释并构造 [ActionContext]。
 */
data class ActionSpec(
    val reqName: String,
    val isMutation: Boolean,
    val actor: ActorRequirement = ActorRequirement.CUSTOMER,
    val requireProjectId: Boolean = true,
)
