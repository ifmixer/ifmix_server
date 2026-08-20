package com.ifmix.api.core.modules.payment.repo

import com.ifmix.api.core.entity.payment.StoreNotification
import com.ifmix.api.core.entity.payment.platform
import com.ifmix.api.core.entity.payment.purchaseToken
import com.ifmix.api.core.entity.payment.processed
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import com.ifmix.api.core.entity.payment.appId
import com.ifmix.api.core.entity.payment.id

@Repository
class StoreNotificationRepository(sql: KSqlClient) : BaseAppCrudRepository<StoreNotification>(sql, StoreNotification::class) {

    fun existsByPlatformAndToken(ctx: ModuleCtx, platform: String, purchaseToken: String): Boolean {
        return ctx.sql.createQuery(StoreNotification::class) {
            where(table.platform eq platform)
            where(table.purchaseToken eq purchaseToken)
            where(table.processed eq true)
            select(table)
        }.limit(1).execute().isNotEmpty()
    }
}
