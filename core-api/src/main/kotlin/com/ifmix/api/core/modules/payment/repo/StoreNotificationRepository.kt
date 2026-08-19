package com.ifmix.api.core.modules.payment.repo

import com.ifmix.api.core.entity.iap.StoreNotification
import com.ifmix.api.core.entity.iap.platform
import com.ifmix.api.core.entity.iap.purchaseToken
import com.ifmix.api.core.entity.iap.processed
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import com.ifmix.api.core.entity.iap.appId
import com.ifmix.api.core.entity.iap.id

@Repository
class StoreNotificationRepository(sql: KSqlClient) : BaseAppCrudRepository<StoreNotification>(sql, StoreNotification::class) {

    fun existsByPlatformAndToken(ctx: SvcCtx, platform: String, purchaseToken: String): Boolean {
        return ctx.sql.createQuery(StoreNotification::class) {
            where(table.platform eq platform)
            where(table.purchaseToken eq purchaseToken)
            where(table.processed eq true)
            select(table)
        }.limit(1).execute().isNotEmpty()
    }
}
