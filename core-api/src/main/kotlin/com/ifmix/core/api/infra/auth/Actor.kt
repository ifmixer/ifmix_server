package com.ifmix.core.api.infra.auth

import com.ifmix.core.api.entity.common.ActorType
import java.util.UUID

/** 解析成功的主体（token 校验通过）。 */
data class Actor(
    val actorId: UUID,
    val actorType: ActorType,
    val anonymous: Boolean,
    /** sessionId（token sid claim）。为将来 Redis session 预留，可能为 null（旧 token）。 */
    val sessionId: String? = null,
)
