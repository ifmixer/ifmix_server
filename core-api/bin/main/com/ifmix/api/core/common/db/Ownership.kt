package com.ifmix.api.core.common.db

import com.ifmix.api.core.common.http.RequestContext
import org.springframework.data.mongodb.core.query.Criteria

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

/**
 * 归属查询条件：登录时 userId OR installId；匿名仅 installId。
 *
 * 用于在 MongoDB 查询中过滤出当前用户拥有的行。
 */
fun ownerCriteria(ctx: RequestContext): Criteria {
    val install = Criteria.where("installId").`is`(ctx.installId)
    return if (ctx.userId != null) {
        Criteria().orOperator(Criteria.where("userId").`is`(ctx.userId), install)
    } else {
        install
    }
}
