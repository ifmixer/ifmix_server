package com.ifmix.api.core.modules.payment.repo

import com.ifmix.api.core.entity.payment.StoreNotification
import com.ifmix.api.core.entity.payment.appId
import com.ifmix.api.core.entity.payment.id
import com.ifmix.api.core.entity.payment.platform
import com.ifmix.api.core.entity.payment.processed
import com.ifmix.api.core.entity.payment.purchaseToken
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.AppCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository

@Repository
class StoreNotificationRepository {
    companion object { private val tpl = AppCrudRepoTemplate(StoreNotification::class) }

    fun existsByPlatformAndToken(mc: ModuleCtx, platform: String, purchaseToken: String): Boolean {
        return mc.sql.createQuery(StoreNotification::class) {
            where(table.platform eq platform)
            where(table.purchaseToken eq purchaseToken)
            where(table.processed eq true)
            select(table)
        }.limit(1).execute().isNotEmpty()
    }

    fun save(mc: ModuleCtx, entity: StoreNotification) = tpl.save(mc, entity)
}
