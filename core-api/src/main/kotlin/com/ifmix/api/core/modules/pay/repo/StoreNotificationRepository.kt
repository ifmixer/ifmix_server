package com.ifmix.api.core.modules.pay.repo

import com.ifmix.api.core.entity.pay.StoreNotification
import com.ifmix.api.core.entity.pay.appId
import com.ifmix.api.core.entity.pay.id
import com.ifmix.api.core.entity.pay.platform
import com.ifmix.api.core.entity.pay.processed
import com.ifmix.api.core.entity.pay.purchaseToken
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
