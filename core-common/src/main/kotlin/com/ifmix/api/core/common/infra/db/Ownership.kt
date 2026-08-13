package com.ifmix.api.core.common.infra.db

import com.ifmix.api.core.common.infra.http.OperationContext
import java.util.UUID

/**
 * 行归属判定：
 * - 登录用户（ctx.userId != null）：只看 row.userId 是否匹配
 * - 匿名用户（ctx.userId == null）：只看 row.installId 是否匹配
 *
 * @param ctx 当前请求上下文
 * @param userId 行记录中的 userId（可为 null）
 * @param installId 行记录中的 installId（可为 null）
 * @return 当前用户是否拥有该行
 */
fun ownsRow(ctx: OperationContext, userId: UUID?, installId: UUID?): Boolean =
    if (userId != null) {
        userId == ctx.userId
    } else if (installId != null) {
        installId == ctx.installId
    } else {
        true
    }
