package com.ifmix.core.api.infra.db

import com.ifmix.core.api.infra.http.OperationContext
import java.util.UUID

/**
 * 行归属判定：只认 customerId。
 * - 行无归属（customerId == null）：视为公共/无主，任何人可访问
 * - 行有归属：必须与当前上下文 customerId 一致
 *
 * @param ctx 当前请求上下文
 * @param customerId 行记录中的 customerId（可为 null）
 * @return 当前用户是否拥有该行
 */
fun ownsRow(ctx: OperationContext, customerId: UUID?): Boolean =
    customerId == null || customerId == ctx.actorId
