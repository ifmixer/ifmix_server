package com.ifmix.api.core.common.db

import com.ifmix.api.core.common.http.RequestContext

/**
 * 行归属判定：登录 userId 或 installId 命中；匿名仅 installId。
 *
 * @param ctx 当前请求上下文
 * @param userId 行记录中的 userId（可为 null）
 * @param installId 行记录中的 installId（可为 null）
 * @return 当前用户是否拥有该行
 */
fun ownsRow(ctx: RequestContext, userId: String?, installId: String?): Boolean =
    (ctx.userId != null && userId == ctx.userId) || (installId != null && installId == ctx.installId)
